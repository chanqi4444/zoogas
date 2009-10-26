import java.lang.*;
import java.util.*;
import java.text.*;
import java.awt.*;
import java.net.*;
import java.io.*;

// Particle class, encapsulating the behavior, appearance & summary statistics of a given CA state
public class Particle {
    // appearance
    public static int maxNameLength = 256;  // maximum length of a Particle name. Introduced to stop runaway regex rules from crashing the engine
    public String name = null;  // noun uniquely identifying this Particle (no whitespace)
    public Color color = null;

    // the PatternSet, i.e. the authority for all transformation, energy and color rules about this Particle
    PatternSet patternSet = null;

    // transformation rules
    protected IdentityHashMap<Particle,RandomVariable<ParticlePair>>[] pattern = null;  // production rules; array is indexed by neighbor direction, Map is indexed by Particle
    protected TransformRuleMatch[][] patternTemplate = null;  // generators for production rules; outer array is indexed by neighbor direction, inner array is the set of partially-bound rules for that direction

    // energy rules
    // TODO: add code to populate and use these!
    protected IdentityHashMap<Particle,Double> energy = null;  // interaction energies; Map is indexed by Particle
    protected EnergyRuleMatch[] energyTemplate = null;  // generators for interaction energies

    // internals
    protected int count = 0;  // how many of this type on the board

    // static variables
    public static String
	visibleSeparatorChar = "/",
	visibleSpaceChar = "_";

    // constructors
    public Particle (String name, Color color, Board board, PatternSet ps) {
	this(name,color,board);
	patternSet = ps;
    }

    public Particle (String name, Color color, Board board) {
	this.name = name.length() > maxNameLength ? name.substring(0,maxNameLength) : name;
	this.color = color;
	board.registerParticle (name, this);
	// The following is what we really want here, but backward compatibility of Java generics prevents initialization of an array of generics:
	//	pattern = new IdentityHashMap<Particle,RandomVariable<ParticlePair>> [board.neighborhoodSize()];
	pattern = new IdentityHashMap[board.neighborhoodSize()];   // causes an unavoidable warning. Thanks, Java!
	patternTemplate = new TransformRuleMatch[board.neighborhoodSize()][];
	for (int n = 0; n < pattern.length; ++n)
	    pattern[n] = new IdentityHashMap<Particle,RandomVariable<ParticlePair>>();
    }

    // methods
    // part of name visible to player
    String visibleName() {
	
	String[] partsOfName = name.split (visibleSeparatorChar, 2);
	String viz = partsOfName[0].replaceAll (visibleSpaceChar, " ");

	// Uncomment to reveal invisible metainfo to player
	//	viz = name;

	return viz;
    }

    // helper to "close" all patterns, adding a do-nothing rule for patterns whose RHS probabilities sum to <1
    void closePatterns() {
	for (int n = 0; n < pattern.length; ++n) {
	    Iterator<Map.Entry<Particle,RandomVariable<ParticlePair>>> iter = pattern[n].entrySet().iterator();
	    while (iter.hasNext()) {
		Map.Entry<Particle,RandomVariable<ParticlePair>> keyval = (Map.Entry<Particle,RandomVariable<ParticlePair>>) iter.next();
		Particle target = (Particle) keyval.getKey();
		//		System.err.println ("Closing pattern " + name + " " + target.name + " -> ...");
		RandomVariable<ParticlePair> rv = (RandomVariable<ParticlePair>) keyval.getValue();
		if (rv != null)
		    rv.close(new ParticlePair (this, target));
	    }
	}
    }

    // method to test if a Particle is active (i.e. has any rules) in a given direction
    boolean isActive(int dir) { return pattern[dir].size() > 0; }

    // helper to sample a new (source,target) pair
    // returns null if no rule found
    ParticlePair samplePair (int dir, Particle oldTarget, Random rnd, Board board) {
	RandomVariable rv = (RandomVariable) pattern[dir].get (oldTarget);
	// if no RV, look for rule generator(s) that match this neighbor, and use them to create a set of rules
	if (rv == null && patternSet != null)
	    rv = patternSet.compileTargetRules(dir,this,oldTarget,board);
	// have we got an RV?
	if (rv != null)
	    return (ParticlePair) rv.sample(rnd);
	// no RV; return null
	return null;
    }
}
