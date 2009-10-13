import java.lang.*;
import java.util.*;
import java.util.regex.*;
import java.text.*;
import java.awt.*;
import java.net.*;
import java.io.*;


// Here is a hypothetical syntax for regexp-based pattern generators:
//  A B C D P V
// where
//  A is a regexp that must globally match the old source state
//  B is a regexp that must globally match the old target state
//  C is a string that will expand to the new source state
//  D is a string that will expand to the new target state
//  P is a numeric constant that is the probability of the rule
//  V is a verb describing the action being carried out by the source when this rule fires (no whitespace)

// The following "special variables" will be expanded in {A,B,C,D} as appropriate:
//  $1,$2,$3... => groups in A and B regexps (c.f. Perl)
//  $S,$T => full names for old source,target states
//  $F,$L,$R,$B => directions relative to neighbor direction ($F=forward, $L=left, $R=right, $B=back)
//  $-1 => numerically one less than $1
//  $--1 => numerically two less than $1 (and $---1 is three less, etc.); negative numbers evaluate to the empty string
//  $+1 => numerically one greater than $1
// (similarly for $-2, $++3, etc.)
//  $%3++1 => ($1 + 2) mod 3
//  $%M+{k}N => ($N + k) mod M   ...where +{k} denotes a run of k plus(+) characters

// A matching rule should overwrite any previously matched rules, allowing us to create exceptions
// (e.g. "destroy any particle; DO NOT destroy basalt").
// UPDATE: this is now implicit in RandomVariable.add()

// Patterns such as this are to be transmitted in a "Particle definition" packet with the following structure:
// NAMES & COLORS (one per line, format "NAME R G B", describing appearances of Particles to which this definition packet applies)
// RULES (one per line, format "A B C D P V")

public class RuleMatch {
    // data
    private RulePattern pattern = null;
    private Pattern aPattern = null, bPattern = null;
    private int dir = -1;
    private String A = null, B = null;
    private Matcher am = null, bm = null;
    private boolean aMatched = false, bMatched = false;
    ZooGas gas = null;

    // constructors
    public RuleMatch(RulePattern p) { pattern = p; }
    public RuleMatch(RulePattern p,ZooGas gas,int dir) { this(p); bindDir(gas,dir); }
    public RuleMatch(RulePattern p,ZooGas gas,int dir,String a) { this(p,gas,dir); bindSource(a); }
    public RuleMatch(RulePattern p,ZooGas gas,int dir,String a,String b) { this(p,gas,dir,a); bindTarget(b); }

    // lhs methods
    // binding
    void bindDir(ZooGas g,int d) {
	if (dir < 0) {
	    gas = g;
	    dir = d;
	    aPattern = Pattern.compile(A());
	}
    }

    void bindSource(String a) {
	if (A == null) {
	    A = a;
	    am = aPattern.matcher(a);
	    aMatched = am.matches();
	    if (aMatched)
		bPattern = Pattern.compile(B());
	}
    }

    void bindTarget(String b) {
	if (B == null) {
	    B = b;
	    bm = bPattern.matcher(b);
	    bMatched = bm.matches();
	}
    }

    // rule matching
    boolean matches() {
	return
	    am == null
	    ? true
	    : (aMatched && (bm == null
				? true
				: bMatched));
    }

    // expanded string methods
    String A() { return expandDir(pattern.A); }
    String B() { return expandLHS(pattern.B); }
    String C() { return expandRHS(pattern.C); }
    String D() { return expandRHS(pattern.D); }

    // main expand() methods
    // expansion of B
    protected String expandLHS (String s) {
	return expandGroupOrSource(expandDir(s));
    }

    // expansion of C and D
    protected String expandRHS (String s) {
	return expandMod(expandDec(expandInc(expandTarget(expandLHS(s)))));
    }

    // expansion of $F, $B, $L, $R
    static Pattern dirPattern = Pattern.compile("\\$([FBLR])");
    protected String expandDir (String s) {
	Matcher m = dirPattern.matcher(s);
	StringBuffer sb = new StringBuffer();
	while (m.find()) {
	    String var = m.group(1);
	    if (var.equals("F"))
		m.appendReplacement(sb,gas.dirString(dir));
	    else if (var.equals("B"))
		m.appendReplacement(sb,gas.dirString((dir + 2) % 4));
	    else if (var.equals("L"))
		m.appendReplacement(sb,gas.dirString((dir + 1) % 4));
	    else if (var.equals("R"))
		m.appendReplacement(sb,gas.dirString((dir + 3) % 4));
	}
	m.appendTail(sb);
	return sb.toString();
    }

    // expansion of $1, $2, ... and $S
    static Pattern groupPattern = Pattern.compile("\\$(S|[1-9][0-9]*)");
    protected String expandGroupOrSource (String s) {
	Matcher m = groupPattern.matcher(s);
	StringBuffer sb = new StringBuffer();
	while (m.find()) {
	    String g = m.group(1);
	    if (g.equals("S"))
		m.appendReplacement(sb,A);
	    else
		m.appendReplacement(sb,getGroup(g));
	}
	m.appendTail(sb);
	return sb.toString();
    }

    // expansion of $T
    static Pattern targetPattern = Pattern.compile("\\$T");
    protected String expandTarget (String s) {
	Matcher m = targetPattern.matcher(s);
	StringBuffer sb = new StringBuffer();
	while (m.find())
	    m.appendReplacement(sb,B);
	m.appendTail(sb);
	return sb.toString();
    }

    // expansion of $+++1
    static Pattern incGroupPattern = Pattern.compile("\\$([\\+]+)([1-9][0-9]*)");
    protected String expandInc (String s) {
	Matcher m = incGroupPattern.matcher(s);
	StringBuffer sb = new StringBuffer();
	while (m.find()) {
	    String inc = m.group(1), g = m.group(2);
	    int n = Integer.parseInt(getGroup(g));
	    int delta = inc.length();
	    m.appendReplacement(sb,Integer.toString(n+delta));
	}
	m.appendTail(sb);
	return sb.toString();
    }

    // expansion of $--1
    static Pattern decGroupPattern = Pattern.compile("\\$([\\-]+)([1-9][0-9]*)");
    protected String expandDec (String s) {
	Matcher m = decGroupPattern.matcher(s);
	StringBuffer sb = new StringBuffer();
	while (m.find()) {
	    String dec = m.group(1), g = m.group(2);
	    int n = Integer.parseInt(getGroup(g));
	    int delta = dec.length();
	    if (n >= delta)
		m.appendReplacement(sb,Integer.toString(n-delta));
	}
	m.appendTail(sb);
	return sb.toString();
    }

    // expansion of $%3++1
    static Pattern modGroupPattern = Pattern.compile("\\$%([1-9][0-9]*)([\\+]+)([1-9][0-9]*)");
    protected String expandMod (String s) {
	Matcher m = modGroupPattern.matcher(s);
	StringBuffer sb = new StringBuffer();
	while (m.find()) {
	    String mod = m.group(1), inc = m.group(2), g = m.group(3);
	    int n = Integer.parseInt(getGroup(g));
	    int M = Integer.parseInt(mod);
	    int delta = inc.length();
	    m.appendReplacement(sb,Integer.toString((n+delta)%M));
	}
	m.appendTail(sb);
	return sb.toString();
    }

    // helper method to get a group ($1,$2,...) from AB
    String getGroup(String group) {
	String val = "";
	try {
	    int n = new Integer(group).intValue();
	    if (n <= am.groupCount())
		val = am.group(n);
	    else if (bm != null) {
		n -= am.groupCount();
		if (n <= bm.groupCount())
		    val = bm.group(n);
	    }
	} catch (NumberFormatException e) { }
	return val;
    }
}
