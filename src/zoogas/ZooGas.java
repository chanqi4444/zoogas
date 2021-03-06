package zoogas;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Image;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.image.BufferedImage;

import java.io.File;
import java.io.IOException;

import java.net.InetSocketAddress;

import java.util.*;

import javax.imageio.ImageIO;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.border.LineBorder;
import javax.swing.event.MouseInputAdapter;

import zoogas.core.Board;
import zoogas.core.Particle;
import zoogas.core.Challenge;

import zoogas.core.Point;

import zoogas.core.SprayTool;
import zoogas.core.rules.ParticleSet;

import zoogas.core.rules.RuleMatch;

import zoogas.core.rules.RuleSyntax;

import zoogas.gui.BoardRenderer;
import zoogas.gui.PlayerRenderer;
import zoogas.gui.ToolBox;

import zoogas.network.ClientToServer;

public class ZooGas implements KeyListener {

    // constants
    final static String spaceParticleName = "_";
    Particle spaceParticle = null;

    // command-line argument defaults
    static int defaultPort = 4444;
    public static String defaultPatternSetFilename = "ECOLOGY.txt", defaultToolboxFilename = "TOOLS.txt";
    static int defaultBoardSize = 128;
    static int defaultTargetUpdateRate = 50; // reduced to run on my 2yr-old Macbook Air - IH, 3/23/2010
    static double cacheFlushFraction = .5; // when this fraction of the heap is used, flush all caches
    int cacheFlushes = 0;

    // size of board in cells
    int size = defaultBoardSize;

    // board
    private Board board = null;

    // pattern set
    String patternSetFilename = defaultPatternSetFilename;

    // Initial conditions; or, How To Build a Zoo.
    // if initImageFilename is not null, then the image will be read from a file,
    // and converted to the Particles in initParticleFilename.
    // if initImageFilename is null, then it is assumed that a miniprogram to build a zoo
    // is contained in the initially-loaded pattern set.
    // in this case, the entire zoo will be initialized to particle "spaceParticleName",
    // and one initialization particle "INIT/radius" (where "radius" is half the size of the zoo) will be placed at coords (radius,radius).
    String initImageFilename = null;
    //    String initImageFilename = "TheZoo.bmp";  // if non-null, initialization loads a seed image from this filename
    String initParticleFilename = "TheZooParticles.txt";
    String initParticlePrefix = "INIT";

    // tools and cheats
    String toolboxFilename = defaultToolboxFilename;
    ToolBox toolBox = null;
    final char cheatKey = '/'; // allows player to see the hidden parts of state names, i.e. the part behind the '/'
    final char stopKey = '.'; // stops the action on this board (does not block incoming network events)
    final char slowKey = ','; // allows player to see bonds

    // commentator code ("well done"-type messages)
    long boardUpdateCount = 0;
    long[] timeFirstTrue = new long[100]; // indexed by row: tracks the first time when various conditions are true, so that the messages flash at first

    // Challenge givers
    // TODO: create several different challenge givers, with different personalities,
    // e.g. add a mean zookeeper who likes to see animals tormented.
    // Completing a challenge-giver's quest adds to that NPC's score,
    // eventually getting them promoted (possibly to the detriment of the other challenge-givers).
    Challenge.Giver challengeGiver = new Challenge.Giver(this);

    // constant helper vars
    double patternMatchesPerRefresh = 1;

    // Swing
    Cursor boardCursor, normalCursor;
    // Uncomment to use "helicopter.png" as a mouse cursor over the board:
    //    String boardCursorFilename = "helicopter.png";
    String boardCursorFilename = null;
    java.awt.Point boardCursorHotSpot = new java.awt.Point(50, 50); // ignored unless boardCursorFilename != null

    // view
    JFrame zooGasFrame;
    JPanel boardPanel;
    JPanel toolBoxPanel;
    JPanel statusPanel;
    BoardRenderer renderer;
    int boardSize; // width & height of board in pixels
    int belowBoardHeight = 0; // size in pixels of whatever appears below the board -- currently unused but left as a placeholder
    int toolBarWidth = 140, toolLabelWidth = 200, toolHeight = 22; // size in pixels of various parts of the tool bar (right of the board)
    int textBarWidth = 400, textHeight = 30;

    // verb history / subtitle track
    private int verbHistoryLength = 10, verbHistoryRefreshPeriod = 20, verbHistoryRefreshCounter = 0;
    public int verbHistoryPos = 0, verbsSinceLastRefresh = 0;
    public String[] verbHistory = new String[verbHistoryLength];
    public Particle[] particleHistory = new Particle[verbHistoryLength];
    public Point[] placeHistory = new Point[verbHistoryLength];
    public int[] verbHistoryAge = new int[verbHistoryLength];

    private final int updatesRow = 0, titleRow = 4, networkRow = 5, objectiveRow = 7;

    // helper objects
    Point cursorPos = new Point(); // co-ordinates of cell beneath current mouse position
    boolean mouseDown = false; // true if mouse is currently down
    boolean cheatPressed = false; // true if cheatKey is pressed (allows player to see hidden parts of state names)
    boolean stopPressed = false; // true if stopKey is pressed (stops updates on this board)
    boolean slowPressed = false; // true if slowKey is pressed (slows updates on this board)
    int targetUpdateRate = defaultTargetUpdateRate;
    double updatesPerSecond = 0;
    long timeCheckPeriod = 20; // board refreshes between calling challengeGiver.check() and recalculating debug stats (updatesPerSecond)
    String lastDumpStats = ""; // hacky way to avoid concurrency issues

    // connection
    protected ClientToServer toWorldServer = null;

    // main()
    public static void main(String[] args) {
        main(args, null);
    }
    public static void main(String[] args, ClientToServer toWorldServer) {
        // create ZooGas object
        ZooGas gas = new ZooGas();
        if (toWorldServer != null) {
            gas.toWorldServer = toWorldServer;
            toWorldServer.setInterface(gas);
        }

        // Process options and args before initializing ZooGas
        boolean isServer = false;
        boolean isClient = false;
        int port = defaultPort;
        String socketAddress = null;

        for (int i = 0; i < args.length; ++i) {
            if ("-s".equals(args[i]) || "--server".equals(args[i])) {
                isServer = true;
            }
            else if ("-c".equals(args[i]) || "--client".equals(args[i])) {
                if (i + 1 >= args.length) {
                    System.err.println("Error: not enough parameters given");
                    System.err.println("-c/--client usage: [-c|--client] <remote address>[:<remote port>]");
                    System.exit(0);
                    return;
                }
                socketAddress = args[++i];
                isServer = isClient = true;
            }
            else if ("-p".equals(args[i]) || "--port".equals(args[i])) {
                if (i + 1 >= args.length) {
                    System.err.println("Error: not enough parameters given");
                    System.err.println("-p/--port usage: [-p|--port] <port>");
                    System.exit(0);
                    return;
                }
                port = (new Integer(args[++i]));
                isServer = true;
            }
            else if ("-t".equals(args[i]) || "--tools".equals(args[i])) {
                if (i + 1 >= args.length) {
                    System.err.println("Error: no tools file specified");
                    System.err.println("-t/--tools usage: [-t|--tools] <tools file>");
                    System.exit(0);
                    return;
                }
                gas.toolboxFilename = args[++i];
            }
            else if ("-r".equals(args[i]) || "--rules".equals(args[i])) {
                if (i + 1 >= args.length) {
                    System.err.println("Error: no rules file specified");
                    System.err.println("-r/--rules usage: [-r|--rules] <rules file>");
                    System.exit(0);
                    return;
                }
                gas.patternSetFilename = args[++i];
            }
            else if ("-u".equals(args[i]) || "--updates".equals(args[i])) {
                if (i + 1 >= args.length) {
                    System.err.println("Error: no update rate parameter specified");
                    System.err.println("-u/--updates usage: [-u|--updates] <target update rate>");
                    System.exit(0);
                    return;
                }
                gas.targetUpdateRate = Integer.parseInt(args[++i]);
            }
            else if ("-?".equals(args[i]) || "-h".equals(args[i]) || "--help".equals(args[i])) {
                System.err.println("Usage: <progname> [<option> [<args>]]");
                System.err.println("Valid options:");
                System.err.println("\t[-c|--client <remote address>[:<remote port>]]");
                System.err.println("\t                     - Start ZooGas in client mode");
                System.err.println("\t[-s|--server]        - Start ZooGas in server mode");
                System.err.println("\t[-p|--p <port>]      - Use <port> as the server port (default " + defaultPort + ")");
                System.err.println("\t[-t|--tools <file>]  - Load tools from specified file (default \"" + defaultToolboxFilename + "\")");
                System.err.println("\t[-r|--rules <file>]  - Load rules from specified file (default \"" + defaultPatternSetFilename + "\")");
                System.err.println("\t[-u|--updates <n>]   - Specify desired updates per second (default " + defaultTargetUpdateRate + ")");
                System.err.println("\t[-?|-h|--help]       - Display this very useful help message");
                System.exit(0);
                return;
            }
            else {
                // Unknown option
                System.err.println("Error: Unknown option: " + args[i]);
                System.exit(0);
                return;
            }
        }

        // initialize after options have been considered
        gas.renderer = new PlayerRenderer(gas, gas.board, gas.size);
        if (isServer) // start as server
            gas.board.initServer(port, gas);

        InetSocketAddress serverAddr = null;
        if (isClient) // start as client (and server)
        {
            String[] address = socketAddress.split(":");
            if (address.length > 1) {
                serverAddr = new InetSocketAddress(address[0], new Integer(address[1]));
            }
            else {
                serverAddr = new InetSocketAddress(address[0], defaultPort);
            }
        }

        gas.board.loadPatternSetFromFile(gas.patternSetFilename);
        gas.spaceParticle = gas.board.initSpaceParticle(spaceParticleName);
        gas.start(serverAddr);
    }

    public ZooGas() {
        // create board (needed from some options), then wait for start to be called
        board = new Board(size);
    }

    public void start(InetSocketAddress serverAddr) {
        // set helpers, etc.
        boardSize = board.getBoardSize(size, renderer.getPixelsPerCell());

        // init board
        if (initImageFilename != null) {
            try {
                BufferedImage img = ImageIO.read(new File(initImageFilename));
                ParticleSet imgParticle = ParticleSet.fromFile(initParticleFilename);
                board.initFromImage(img, imgParticle);

            }
            catch (IOException e) {
                e.printStackTrace();
            }
        }
        else {
            board.fill(spaceParticle);
            String initParticleName = initParticlePrefix + '/' + String.valueOf(size / 2);
            Particle initParticle = board.getOrCreateParticle(initParticleName);
            if (initParticle == null)
                throw new RuntimeException("Initialization particle " + initParticleName + " not found");
            Point initPoint = new Point(size / 2, size / 2);
            board.writeCell(initPoint, initParticle);
        }

        // init spray tools
        initSprayTools();

        // init objective

        // TODO: create the following challenge sequence:
        // - place 5 animals
        // - place 5 guests
        // - create an enclosure
        // - while keeping at least 5 guests alive, do each of the following:
        //  - (once only) get animal population over 100
        //  - (for at least 30 seconds) maintain species diversity at ~2.9 species or better, i.e. keep entropy of species distribution above log(2.9)
        //  - (for at least 30 seconds) maintain species diversity at ~3.9 species or better, i.e. keep entropy of species distribution above log(3.9)
        // - keep at least 5 guests alive, and species diversity at ~3.9 or better, while the computer makes your life hell by...
        //  - spraying a random burst of animals around a random location in the zoo every 10 seconds
        //  - spraying a low-intensity acid storm all over the zoo
        //  - a volcano erupts at a random location in the zoo, pouring lava everywhere
        //  - one of your zoo guests starts spraying perfume everywhere
        //  - one of your zoo guests turns into a terrorist, throwing bombs all over the place


        // TODO: challenges should be able provide challenge-specific scores, feedback and rewards;
        // e.g. (at a minimum) the diversity scores and particle counts that are currently displayed.


        // hackish test cases (kept here for reference)
        // place 5 guests anywhere
        challengeGiver.addObjective(new Challenge(this, new Challenge.EncloseParticles(5, "zoo_guest", board)));
        // create 4 separated enclosures
        int minEncSize = 16, maxEncSize = board.size * board.size / 2;
        challengeGiver.addObjective(new Challenge(this, new Challenge.EnclosuresCondition(this, null, 4, minEncSize, maxEncSize, false, "wall")));
        // create 3 separated enclosures with 4 zoo_guests in each
        challengeGiver.addObjective(new Challenge(this, new Challenge.EnclosuresCondition(this, new Challenge.EncloseParticles(4, "zoo_guest", board), 3, minEncSize, maxEncSize, false, "wall")));
        // keep a zoo_guest alive for 50 updates
        challengeGiver.addObjective(new Challenge(this, new Challenge.SucceedNTimes(this, 50, new Challenge.EncloseParticles(1, "zoo_guest", board))));

        // place 5 animals anywhere
        challengeGiver.addObjective(new Challenge(this, new Challenge.EncloseParticles(5, "critter", board)));
        // keep animal population at 10 and diversity score at 3.5 for 50 updates
        challengeGiver.addObjective(new Challenge(this, new Challenge.SucceedNTimes(this, 50, new Challenge.EnclosedParticleEntropy(10, "critter", 3.5, board))));

        // throw a bomb in at a random location
        challengeGiver.addObjective(new Challenge(this, new Challenge.SucceedNTimes(this, 20, new Challenge.OrCondition(new Challenge.SprayEvent(board, renderer, new SprayTool(board, "bomb", 1, 1, 1, 1)), new Challenge.TrueCondition()))));

        // init hints
        String specialKeys = "Special keys: " + cheatKey + " (reveal state) " + slowKey + " (reveal bonds) " + stopKey + " (freeze)";
        challengeGiver.addHint("Deputy Head Zookeeper, Celia O'Tuamata.");
        challengeGiver.addHint("Make a zoo using the tools in your Toolbox (left).");
        challengeGiver.addHint("Select a tool by clicking, or press its hot-key.");
        challengeGiver.addHint("Try building a cage.");
        if (toolBox.getTools().size() > 0)
            challengeGiver.addHint("Press \"" + toolBox.getTools().get(0).getHotKey() + "\" to select " + toolBox.getTools().get(0).getParticleName() + "; etc.");
        challengeGiver.addHint("Click on the board to use the currently selected tool.");
        challengeGiver.addHint("Hold down the tool hotkey with the mouse over the board.");
        if (toolBox.getTools().size() > 0)
            challengeGiver.addHint("Mouseover the board & hold \"" + toolBox.getTools().get(0).getHotKey() + "\" to spray " + toolBox.getTools().get(0).getParticleName() + " pixels; etc.");
        challengeGiver.addHint("Use cage-builders to get your zoo started.");
        challengeGiver.addHint("Next to each tool is a bar showing the reserve.");
        challengeGiver.addHint("If you mouseover a pixel on the board, its name appears.");
        challengeGiver.addHint("When you build a cage, it contains a few animals.");
        challengeGiver.addHint(specialKeys);
        challengeGiver.addHint("The \"" + cheatKey + "\" key reveals the hidden state of a pixel.");
        challengeGiver.addHint(specialKeys);
        challengeGiver.addHint("The \"" + cheatKey + "\" key reveals outgoing(>) and incoming(<) bonds.");
        challengeGiver.addHint("The \"" + slowKey + "\" key reveals separated spaces.");
        challengeGiver.addHint("The \"" + stopKey + "\" key stops all action on the board.");
        challengeGiver.addHint("Keep cage walls in good repair, or animals will escape.");
        challengeGiver.addHint(specialKeys);
        challengeGiver.addHint("The \"" + cheatKey + "\" key reveals the number of pixels in existence.");
        challengeGiver.addHint("The \"" + slowKey + "\" key draws bonds on the board.");

        // init JFrame
        zooGasFrame = new JFrame("ZooGas");
        JPanel contentPane = (JPanel)zooGasFrame.getContentPane();
        zooGasFrame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        zooGasFrame.setResizable(false);

        boardPanel = new JPanel() {
                public void paintComponent(Graphics g) {
                    //super.paintComponent(g);
                    g.drawImage(renderer.getImage(), 0, 0, null);
                    if (slowPressed) {
                        drawBonds(g);
                        drawEnclosures(g);
                    }
                    drawVerbs(g);
                    drawCursorNoun(g);
                }
            };
        toolBoxPanel = new JPanel() {
                public void paintComponent(Graphics g) {
                    super.paintComponent(g);
                    drawToolbox(g);
                }
            };
        statusPanel = new JPanel() {
                public void paintComponent(Graphics g) {
                    super.paintComponent(g);
                    drawStatus(g);
                }
            };

        boardPanel.setDoubleBuffered(false);
        toolBoxPanel.setDoubleBuffered(false);
        statusPanel.setDoubleBuffered(false);

        contentPane.setBackground(Color.BLACK);
        boardPanel.setBackground(Color.BLACK);
        toolBoxPanel.setBackground(Color.BLACK);
        statusPanel.setBackground(Color.BLACK);

        // set size
        boardPanel.setPreferredSize(new Dimension(boardSize, boardSize));
        toolBoxPanel.setPreferredSize(new Dimension(toolBarWidth + toolLabelWidth, Math.max(boardSize, toolBox.getTools().size() * toolHeight)));
        statusPanel.setPreferredSize(new Dimension(textBarWidth, boardSize));

        boardPanel.setBorder(new LineBorder(Color.white, 1));

        // add to content pane using layout
        contentPane.setLayout(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        contentPane.add(boardPanel, c);
        ++c.gridx;
        contentPane.add(toolBoxPanel, c);
        ++c.gridx;
        contentPane.add(statusPanel, c);

        zooGasFrame.pack();
        zooGasFrame.setVisible(true);

        // create cursors
        boardCursor = new Cursor(Cursor.HAND_CURSOR);
        normalCursor = new Cursor(Cursor.DEFAULT_CURSOR);

        // register for mouse & keyboard events
        BoardMouseAdapter boardMouse = new BoardMouseAdapter();
        boardPanel.addMouseListener(boardMouse);
        boardPanel.addMouseMotionListener(boardMouse);

        MouseListener toolsMouse = new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                toolBox.clickSelect(e.getPoint().y);
            }
        };
        toolBoxPanel.addMouseListener(toolsMouse);

        MouseListener statusMouse = new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                challengeGiver.giveHint();
            }
        };
        statusPanel.addMouseListener(statusMouse);
        zooGasFrame.addKeyListener(this);

        // connect to server
        if (serverAddr != null)
            board.initClient(serverAddr);

        // just before running the game, dump the DTD to stderr (TODO: remove this)
        if (RuleSyntax.debugXMLdumpStream != null)
            RuleSyntax.debugXMLdumpStream.println(RuleSyntax.makeDTD());

        // run
        gameLoop();
    }

    // main game loop
    private void gameLoop() {
        // Game logic goes here
        zooGasFrame.repaint();

        Runtime runtime = Runtime.getRuntime();
        long lastTimeCheck = System.currentTimeMillis();
        long updateStartTime = System.currentTimeMillis();
        long targetTimePerUpdate = 1000 / targetUpdateRate;
        long timeDiff;

        try {
            while (true) {
                updateStartTime = System.currentTimeMillis();

                if (!stopPressed)
                    evolveStuff();
                useTools();

                if (boardUpdateCount % timeCheckPeriod == 0) {
                    double heapFraction = ((double)(runtime.totalMemory() - runtime.freeMemory())) / (double)runtime.maxMemory();
                    if (heapFraction > cacheFlushFraction) {
                        ++cacheFlushes;
                        board.flushCaches();
                    }

                    lastDumpStats = board.debugDumpStats();
                    long currentTimeCheck = System.currentTimeMillis();
                    updatesPerSecond = ((double)1000 * timeCheckPeriod) / ((double)(currentTimeCheck - lastTimeCheck));
                    lastTimeCheck = currentTimeCheck;

                    challengeGiver.check();
                    challengeGiver.animate();
                }
                zooGasFrame.repaint();

                timeDiff = System.currentTimeMillis() - updateStartTime;
                if (timeDiff < targetTimePerUpdate) {
                    Thread.currentThread().sleep(targetTimePerUpdate - timeDiff);
                }
            }
        }
        catch (InterruptedException e) {
        }
    }

    // main evolution loop
    private void evolveStuff() {
        board.update(patternMatchesPerRefresh, renderer);
        ++boardUpdateCount;
    }

    // init tools method
    private void initSprayTools() {
        toolBox = ToolBox.fromFile(toolboxFilename, board, toolHeight, toolBarWidth, toolLabelWidth);
    }

    private void useTools() {
        if (board.onBoard(cursorPos)) {
            zooGasFrame.setCursor(boardCursor);

            // do spray
            if (mouseDown && toolBox.getCurrentTool() != null) {
                toolBox.getCurrentTool().spray(cursorPos, board, renderer, spaceParticleName);
                return;
            }
        }
        else
            zooGasFrame.setCursor(normalCursor);

        toolBox.refill(1);
    }

    public Board getBoard() {
        return board;
    }

    public BoardRenderer getBoardRenderer() {
        return renderer;
    }

    public ClientToServer getWorldServerThread() {
        return toWorldServer;
    }

    public boolean isCheatPressed() {
        return cheatPressed;
    }

    /**
     *Refreshes the buffer of the ZooGas frame
     */
    protected void refreshBuffer() {
        // update buffer
        zooGasFrame.getContentPane().repaint();
    }

    /**
     *Draws all active bonds between particles using random colors
     * @param g
     */
    protected void drawBonds(Graphics g) {
        Point p = new Point();
        for (p.x = 0; p.x < board.size; ++p.x) {
            for (p.y = 0; p.y < board.size; ++p.y) {
                for (Map.Entry<String, Point> kv : board.incoming(p).entrySet()) {
                    Point delta = kv.getValue();
                    if (delta != null) {
                        Point q = p.add(kv.getValue());
                        if (board.onBoard(q))
                            drawBond(g, p, q);
                        /* TODO: consider using new Points in this loop
			 */
                    }
                }
            }
        }
    }
    private void drawBond(Graphics g, Point p, Point q) {
        g.setColor(new Color((float)Math.random(), (float)Math.random(), (float)Math.random()));
        Point pg = renderer.getGraphicsCoords(p);
        Point qg = renderer.getGraphicsCoords(q);
        int k = renderer.getPixelsPerCell() >> 1;
        g.drawLine(pg.x + k, pg.y + k, qg.x + k, qg.y + k);
    }

    // highlight enclosures of size >= 10
    protected void drawEnclosures(Graphics g) {
        String wallPrefix = "wall";
        int minSize = 10;
        Image image = new BufferedImage(boardSize, boardSize, BufferedImage.TYPE_INT_ARGB);
        Graphics ig = image.getGraphics();
        for (List<Point> enclosure : Challenge.getEnclosures(board, wallPrefix, false))
            if (enclosure.size() >= minSize) {
                ig.setColor(new Color((float)Math.random(), (float)Math.random(), (float)Math.random()));
                for (Point p : enclosure) {
                    Point q = renderer.getGraphicsCoords(p);
                    ig.fillRect((int)(q.x + Math.random() * renderer.getPixelsPerCell()), (int)(q.y + Math.random() * renderer.getPixelsPerCell()), 1, 1);
                }
            }

        g.drawImage(image, 0, 0, null);
    }

    /**
     *Draws the status panel
     * @param g
     */
    protected void drawStatus(Graphics g) {
        // name of the game
        flashOrHide(g, "Z00 GAS", titleRow, true, 0, 400, true, Color.white);

        // networking
        flashOrHide(g, "Online", networkRow, board.online(), 0, -1, false, Color.blue);
        flashOrHide(g, "Connected", networkRow + 1, board.connected(), 0, -1, false, Color.cyan);

        // current objective
        int avatarSize = 2 * charHeight(g);
        challengeGiver.drawAvatar(g, textBarWidth - avatarSize, rowYpos(g, objectiveRow), avatarSize, avatarSize);

        // update rate and other stats
        StringBuilder sb = new StringBuilder();
        Formatter formatter = new Formatter(sb, Locale.US);
        Runtime runtime = Runtime.getRuntime();
        printOrHide(g, lastDumpStats, updatesRow, true, new Color(48, 48, 0));
        printOrHide(g, "Heap: current " + kmg(runtime.totalMemory()) + ", max " + kmg(runtime.maxMemory()) + ", free " + kmg(runtime.freeMemory()) + (cacheFlushes > 0 ? (", cache flushes " + cacheFlushes) : ""), updatesRow + 1, true, new Color(48, 48, 0));
        printOrHide(g, formatter.format("Updates/sec: %.2f", updatesPerSecond).toString(), updatesRow + 2, true, new Color(64, 64, 0));
    }

    protected void drawVerbs(Graphics g) {
        // display params
        int maxAge = 100;
        boolean writeNouns = false;
        int verbBalloonBorder = 2;
        int bubbleLines = writeNouns ? 2 : 1;

        // font
        FontMetrics fm = g.getFontMetrics();

        // loop over verb history
        for (int vpos = 0; vpos < verbHistoryLength; ++vpos) {
            int v = (verbHistoryPos + verbHistoryLength - vpos) % verbHistoryLength;

            if (verbHistory[v] != null) {
                if (verbHistoryAge[v]++ >= maxAge)
                    verbHistory[v] = null;
                else {
                    String nounText = cheatPressed ? particleHistory[v].name : particleHistory[v].visibleName();
                    String verbText = cheatPressed ? verbHistory[v] : Particle.visibleText(verbHistory[v]);
                    Color verbColor = particleHistory[v].color;

                    String[] text = new String[bubbleLines];
                    Color[] textColor = new Color[bubbleLines];

                    text[0] = verbText;
                    textColor[0] = verbColor;

                    if (writeNouns) {
                        text[1] = nounText;
                        textColor[1] = verbColor;
                    }

                    drawSpeechBalloon(g, placeHistory[v], 0., -1., verbBalloonBorder, text, textColor, verbColor, Color.black);
                }
            }
        }

        if (++verbHistoryRefreshCounter >= verbHistoryRefreshPeriod)
            verbsSinceLastRefresh = verbHistoryRefreshCounter = 0;
    }

    protected void drawCursorNoun(Graphics g) {
        int nounBalloonBorder = 2;
        if (board.onBoard(cursorPos)) {
            Particle cursorParticle = board.readCell(cursorPos);
            boolean isSpace = cursorParticle == spaceParticle;

            String nameToShow = cheatPressed ? cursorParticle.name + " (" + cursorParticle.getReferenceCount() + ")" + board.singleNeighborhoodDescription(cursorPos, false) : cursorParticle.visibleName();

            if (nameToShow.length() > 0) {
                Color fgCurs = cursorParticle == null ? Color.white : cursorParticle.color;
                Color bgCurs = cheatPressed ? new Color(255 - fgCurs.getRed(), 255 - fgCurs.getGreen(), 255 - fgCurs.getBlue()) : Color.black;

                String[] text = new String[1];
                Color[] textColor = new Color[1];

                text[0] = nameToShow;
                textColor[0] = bgCurs;

                drawSpeechBalloon(g, cursorPos, 0., +3., nounBalloonBorder, text, textColor, null, fgCurs);
            }
        }
    }

    // TODO: drawSpeechBalloon should detect cases where the speech balloon is out of the Panel's paintable area, and adjust its position accordingly
    protected void drawSpeechBalloon(Graphics g, Point cell, double xOffset, double yOffset, int balloonBorder, String[] text, Color[] textColor, Color balloonColor, Color bgColor) {
        java.awt.Point bSize = balloonSize(g, text), cellCoords = renderer.getGraphicsCoords(cell);
        java.awt.Point balloonTopLeft = new java.awt.Point(cellCoords.x + (int)(bSize.x * (xOffset - 0.5)), cellCoords.y + (int)(bSize.y * yOffset));

        drawSpeechBalloonAtGraphicsCoords(g, cellCoords, balloonTopLeft, balloonBorder, text, textColor, balloonColor, balloonColor, bgColor);
    }

    public java.awt.Point balloonSize(Graphics g, String[] text) {
        FontMetrics fm = g.getFontMetrics();

        int xSize = 0, ySize = fm.getHeight();

        for (int n = 0; n < text.length; ++n) {
            xSize = Math.max(xSize, fm.stringWidth(text[n]));
        }

        return new java.awt.Point(xSize, ySize);
    }

    public void drawSpeechBalloonAtGraphicsCoords(Graphics g, java.awt.Point stalkBase, java.awt.Point balloonTopLeft, int balloonBorder, String[] text, Color[] textColor, Color balloonColor, Color stalkColor, Color bgColor) {

        java.awt.Point bSize = balloonSize(g, text);

        // draw speech balloon
        int yTextSize = bSize.y * text.length;

        if (stalkColor != null) {
            g.setColor(stalkColor);
            g.drawLine(balloonTopLeft.x, balloonTopLeft.y, stalkBase.x, stalkBase.y);
        }

        g.setColor(bgColor);
        g.fillRect(balloonTopLeft.x - balloonBorder, balloonTopLeft.y - yTextSize - balloonBorder, bSize.x + 2 * balloonBorder, yTextSize + 2 * balloonBorder);

        for (int n = 0; n < text.length; ++n) {
            g.setColor(textColor[n]);
            g.drawString(text[n], balloonTopLeft.x, balloonTopLeft.y - bSize.y * n);
        }

        if (balloonColor != null) {
            g.setColor(balloonColor);
            g.drawRect(balloonTopLeft.x - balloonBorder, balloonTopLeft.y - yTextSize - balloonBorder, bSize.x + 2 * balloonBorder, yTextSize + 2 * balloonBorder);
        }

    }

    // tool bars
    protected void drawToolbox(Graphics g) {
        // spray levels
        toolBox.plotReserves(g);
    }

    static String kmg(long bytes) {
        return bytes < 1024 ? (bytes + "B") : (bytes < 1048576 ? (bytes / 1024 + "K") : (bytes < 1073741824 ? (bytes / 1048576 + "M") : bytes / 1073741824 + "G"));
    }

    private void flashOrHide(Graphics g, String text, int row, boolean show, int minTime, int maxTime, boolean onceOnly, Color color) {
        int flashPeriod = 10, flashes = 10;
        boolean reallyShow = false;
        boolean currentlyShown = timeFirstTrue[row] > 0;
        if (show) {
            if (!currentlyShown)
                timeFirstTrue[row] = boardUpdateCount;
            else {
                long timeSinceFirstTrue = boardUpdateCount - timeFirstTrue[row];
                long flashesSinceFirstTrue = (timeSinceFirstTrue - minTime) / flashPeriod;
                reallyShow = timeSinceFirstTrue >= minTime && (maxTime < 0 || timeSinceFirstTrue <= maxTime) && ((flashesSinceFirstTrue > 2 * flashes) || (flashesSinceFirstTrue % 2 == 0));
            }
        }
        else if (!onceOnly)
            timeFirstTrue[row] = 0;

        if (reallyShow || currentlyShown)
            printOrHide(g, text, row, reallyShow, color);
    }

    public static int charHeight(Graphics g) {
        return g.getFontMetrics().getHeight();
    }

    public static int stringWidth(Graphics g, String text) {
        return g.getFontMetrics().stringWidth(text);
    }

    private static int bleed = 6;
    private int rowYpos(Graphics g, int row) {
        return row * (charHeight(g) + bleed);
    }

    private void printOrHide(Graphics g, String text, int row, boolean show, Color color) {
        int yPos = rowYpos(g, row);
        if (show && text != null) {
            int xSize = stringWidth(g, text), xPos = textBarWidth - xSize;
            g.setColor(color);
            g.drawString(text, xPos, yPos + charHeight(g));
        }
    }

    private void printOrHide(Graphics g, String text, int row, boolean show, Color color, Color bgColor) {
        int yPos = rowYpos(g, row);
        g.setColor(bgColor);
        g.fillRect(0, yPos, textBarWidth, charHeight(g) + bleed);
        printOrHide(g, text, row, show, color);
    }

    public int getNumVerbsSinceLastRefresh() {
        return verbsSinceLastRefresh;
    }

    public int getVerbHistoryLength() {
        return verbHistoryLength;
    }

    /**
     *Returns the verb at history index i
     * @param i
     * @return String
     */
    public String getVerbHistory(int i) {
        return verbHistory[i];
    }

    /**
     *Returns the noun at history index i
     * @param i
     * @return String
     */
    public Particle getParticleHistory(int i) {
        return particleHistory[i];
    }


    // UI methods
    // mouse events
    private class BoardMouseAdapter extends MouseInputAdapter {
        public void mousePressed(MouseEvent e) {
            mouseDown = true;
        }

        public void mouseReleased(MouseEvent e) {
            mouseDown = false;
        }

        public void mouseEntered(MouseEvent e) {
            mouseDown = false;
        }

        public void mouseExited(MouseEvent e) {
            cursorPos.x = -1;
            cursorPos.y = -1;
            mouseDown = false;
        }

        public void mouseClicked(MouseEvent e) {
            mouseDown = false;
        }

        public void mouseMoved(MouseEvent e) {
            cursorPos = renderer.getCellCoords(e.getPoint());
        }

        public void mouseDragged(MouseEvent e) {
            cursorPos = renderer.getCellCoords(e.getPoint());
        }
    }

    // key events
    public void keyTyped(KeyEvent e) {
    }

    public void keyPressed(KeyEvent e) {
        char c = e.getKeyChar();
        if (toolBox.hotKeyPressed(c))
            mouseDown = true;
        else {
            switch (c) {
                case cheatKey:
                    cheatPressed = true;
                    break;
                case stopKey:
                    stopPressed = true;
                    break;
                case slowKey:
                    slowPressed = true;
                    break;
                case 'm':
                    for (String ss : board.getNameToParticleMap().keySet())
                        System.err.println("Particle " + ss + " count " + board.getNameToParticleMap().get(ss).getReferenceCount());
                    break;
                case '`':
                    if (statusPanel.isVisible()) {
                        statusPanel.setVisible(false);
                        toolBoxPanel.setVisible(false);
                    }
                    else {
                        statusPanel.setVisible(true);
                        toolBoxPanel.setVisible(true);
                    }
                    zooGasFrame.pack();
                    break;
            }
        }
    }

    public void keyReleased(KeyEvent e) {
        mouseDown = false;
        switch (e.getKeyChar()) {
            case cheatKey:
                cheatPressed = false;
                break;
            case stopKey:
                stopPressed = false;
                break;
            case slowKey:
                slowPressed = false;
                break;
        }
    }
}
