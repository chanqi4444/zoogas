import java.lang.*;
import java.util.*;
import java.util.regex.*;
import java.text.*;
import java.awt.*;
import java.net.*;
import java.io.*;


// Syntax for regexp-based pattern generators:
//  A B C D P V
// where
//  A is a regexp that must globally match the old source state
//  B is a regexp that must globally match the old target state
//  C is a string that will expand to the new source state
//  D is a string that will expand to the new target state
//  P is a numeric constant that is the probability of the rule
//  V is a verb describing the action being carried out by the source when this rule fires (no whitespace)

public class RulePattern {
    // data
    String A = null, B = null, C = null, D = null, V = null;
    double P = 0;
    
    // constructors
    public RulePattern (String a, String b, String c, String d, double p, String v) {
	A = a;
	B = b;
	C = c;
	D = d;
	P = p;
	V = v;
    }

    public RulePattern (String abcdpv) {
	String[] args = abcdpv.split(" ",6);
	if (args.length != 6) {
	    throw new RuntimeException ("Rule '" + abcdpv + "' has " + args.length + " args; expected 6");
	}
	A = args[0];
	B = args[1];
	C = args[2];
	D = args[3];
	P = Double.parseDouble(args[4]);
	V = args[5];
    }

    // toString method
    public String toString() { return A + " " + B + " " + C + " " + D + " " + P + " " + V; }
}