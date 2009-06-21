/*
 *  Copyright 2009, Plutext Pty Ltd.
 *   
 *  This file is part of docx4j.

    docx4j is licensed under the Apache License, Version 2.0 (the "License"); 
    you may not use this file except in compliance with the License. 

    You may obtain a copy of the License at 

        http://www.apache.org/licenses/LICENSE-2.0 

    Unless required by applicable law or agreed to in writing, software 
    distributed under the License is distributed on an "AS IS" BASIS, 
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
    See the License for the specific language governing permissions and 
    limitations under the License.

 */

package com.topologi.diffx;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.eclipse.compare.EventSequenceComparator;
import org.eclipse.compare.rangedifferencer.RangeDifference;
import org.eclipse.compare.rangedifferencer.RangeDifferencer;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import com.topologi.diffx.config.DiffXConfig;
import com.topologi.diffx.format.SmartXMLFormatter;
import com.topologi.diffx.load.DOMRecorder;
import com.topologi.diffx.sequence.EventSequence;
import com.topologi.diffx.sequence.PrefixMapping;

/**
 * docx4j uses topologi's diffx project to determine the difference 
 * between two bits of WordML.  (an xslt is then used to convert
 * the diffx output to WordML with the changes tracked)
 * 
 * If the two things being compared start or end with the same
 * XML, diffx slices that off.
 * 
 * After that, you are left with EventSequences representing the
 * two things being compared (an event for the start and end of
 * each element and attributes, and for each word of text).
 * 
 * The problem is that performance drops off rapidly.  For example,
 * if each event sequence is:
 * 
 * + under say 500 entries, time is negligible
 * 
 * + 1800 entries long, calculating the LCS length to fill the matrix
 *   may take 17 seconds (on a 2.4GHZ Core 2 Duo, running from within
 *   Eclipse)
 *
 * + 3000 entries, about 95 seconds (under IKVM)
 *   
 * + 3500 entries, about 120 seconds
 * 
 * + 5500 entries, about 550 seconds (under IKVM)
 *
 * Ultimately, we should migrate to / develop a library which doesn't have 
 * this problem, and supports:
 * 
 * - word level diff
 * - 3 way merge
 * - move
 * 
 * An intermediate step might be to add an implementation of the Lindholm 
 * heuristically guided greedy matcher to the com.topologi.diffx.algorithm
 * package.  See the Fuego Core XML Diff and Patch tool project
 * (which as at 19 June 2009, was offline). Could be relatively straightforward,
 * since it also uses an event sequence concept.
 * 
 * But in the meantime this class attempts to divide up the problem.  The strategy
 * is to look at the children of the nodes passed in, hoping to find
 * an LCS amongst those.  If we have that LCS, then 
 * (at least in the default case) we don't need
 * to diff the things in the LCS, just the things between the
 * LCS entries.  I say 'default case' because in that case 
 * the LCS entries are each the hashcode of the diffx EventSequences.  
 * (But if you
 *  were operating on sdts, you might make them the sdt id.)
 * 
 * This approach might work on the children of w:body (paragraphs,
 * for example), or the children of an sdt:content.
 * 
 * It could also help if you run it on two w:body, where
 * all the w:p are inside w:sdts, provided you make use of the 
 * sdt id's, *and* the sliced event sequences inside the sdt's aren't
 * too long.
 * 
 * We use the eclipse.compare package for the coarse grained divide+conquer.
 * 
 * TODO If any of the diffx sliced event sequence pairs are each > 2000 
 * entries long, this will log a warning, and just return
 * left tree deleted, right tree inserted.  Or try to carve them up somehow?
 * 
 * The classes in src/diffx do not import any of org.docx4j proper;
 * keep it this way so that this package can be made into a dll
 * using IKVM, and used in a .net application, without extra
 * dependencies (though we do use commons-lang, for help in
 * creating good hashcodes).
 * 
 * @author jason
 *
 */
public class Docx4jDriver {
	
	// no logger in this class, to minimise external dependencies.
	// Instead:
	public static final boolean debug = true;
	public static void log(String message, boolean force) {		
		if (debug || force) {
			System.out.println(message);
		}
	}
	public static void log(String message) {		
		log(message, false);
	}
	
	
	public static void diff(Node xml2, Node xml1, Writer out) // swapped, 
			throws DiffXException, IOException {

		DiffXConfig diffxConfig = new DiffXConfig();
		diffxConfig.setIgnoreWhiteSpace(false);
		diffxConfig.setPreserveWhiteSpace(true);
		
//		log(xml1.getNodeName());
//		log(""+ xml1.getChildNodes().getLength());
//		log(xml2.getNodeName());
//		log(""+ xml2.getChildNodes().getLength());
				
		// Root nodes must be the same to do divide+conquer.
		// Even then, only do it if there
		// are more than 3 children.  (If there are 3 children
		// and the first and last are the same, then diffx slice
		// would detect that anyway).		
		if (!xml1.getNodeName().equals(xml2.getNodeName())
			|| (	
				(xml1.getChildNodes().getLength() <= 3)
				&& (xml2.getChildNodes().getLength() <= 3))) {
			// Don't bother with anything tricky
			// (In due course, could try doing it on their 
			// children?)

			// .. just normal diffx
			log("Skipping top level LCS");
			Main.diff(xml1, xml2, out, diffxConfig);
				// The signature which takes Reader objects appears to be broken
			
			out.close();
			return;
		} 
		  
		// Divide and conquer
		
	    DOMRecorder loader = new DOMRecorder();
	    loader.setConfig(diffxConfig);		
		
		log("top level LCS - creating EventSequences...");
		List<EventSequence> leftES = new ArrayList<EventSequence>();
		for (int i = 0 ; i < xml1.getChildNodes().getLength(); i++ ) {
			//log( Integer.toString(xml1.getChildNodes().item(i).getNodeType()));
			
			// A text node at this level is assumed to be pretty printing
			if (xml1.getChildNodes().item(i).getNodeType()!=3) {
				//log("Adding " + xml1.getChildNodes().item(i).getNodeName() );
				Element e = (Element)xml1.getChildNodes().item(i);
				e.setAttributeNS("http://www.w3.org/2000/xmlns/", "xmlns:w", 
						"http://schemas.openxmlformats.org/wordprocessingml/2006/main");
				leftES.add(loader.process( e ));
				//log("" + leftES.get( leftES.size()-1 ).hashCode() );
			}
		}
		EventSequenceComparator leftESC = new EventSequenceComparator(leftES); 

		
		//log("\n\n right");
		List<EventSequence> rightES = new ArrayList<EventSequence>();
		for (int i = 0 ; i < xml2.getChildNodes().getLength(); i++ ) {
			if (xml2.getChildNodes().item(i).getNodeType()!=3) {
				Element e = (Element)xml2.getChildNodes().item(i);
				e.setAttributeNS("http://www.w3.org/2000/xmlns/", "xmlns:w", 
						"http://schemas.openxmlformats.org/wordprocessingml/2006/main");
				rightES.add(loader.process( e ));
				//log("" + rightES.get( rightES.size()-1 ).hashCode() );
			}
		}
		EventSequenceComparator rightESC = new EventSequenceComparator(rightES); 
		
		log("top level LCS - determining top level LCS...");
		RangeDifference[] rd = RangeDifferencer.findDifferences(leftESC, rightESC);
		
		// Debug: Raw output
		for (int i=0; i<rd.length; i++ ) {			
			RangeDifference rdi = rd[i];
			log( rdi.kindString() + " left " + rdi.leftStart() + "," + rdi.leftLength()
					+ " right " + rdi.rightStart() + "," + rdi.rightLength() );
		}
				
		log("top level LCS done; now performing child actions ...");
		
		
	    SmartXMLFormatter formatter = new SmartXMLFormatter(out);
	    formatter.setConfig(diffxConfig);
	    	    
		// Write out parent open element
		// We could create an open event and pass it to the formatter,
	    // but why bother when we can just write directly to Writer out?
	    String rootNodeName = xml1.getNodeName();
	    out.append("<" + rootNodeName  
	    		+ " xmlns:" + xml1.getPrefix() + "=\"" + xml1.getNamespaceURI() + "\""  // w: namespace 
	    		+ " xmlns:dfx=\"" + Constants.BASE_NS + "\""  // Add these, since SmartXMLFormatter only writes them on the first fragment
	    		+ " xmlns:del=\"" + Constants.DELETE_NS + "\""   
	    		+ " xmlns:ins=\"" + Constants.BASE_NS + "\""   
	    				+ " >" );
		
		int leftIdx = 0;
		for (int i=0; i<rd.length; i++ ) {
			
			RangeDifference rdi = rd[i];

			// No change
			if (rdi.leftStart() > leftIdx) {
			
				for (int k = leftIdx ; k< rdi.leftStart() ; k++) {
					// This just goes straight into the output,
					// since it is the same on the left and the right.
					// Since it is the same on both side, we handle
					// it here (on the left side), and
					// ignore it on the right
					out.append("\n<!-- Adding same -->\n");
				    formatter.declarePrefixMapping(leftESC.getItem(k).getPrefixMapping());					
					leftESC.getItem(k).format(formatter);
					out.append("\n<!-- .. Adding same done -->");
					
					// If we wanted to difference sdt's which 
					// were treated the as the same (via their id)
					// this is where we'd have to change
					// (in addition to changing EventSequence for
					//  such things so that hashcode returned their
					//  id!)
				}
				leftIdx = rdi.leftStart(); 
			}
			
			EventSequence seq1 = new EventSequence();
			for (int k = rdi.leftStart() ; k< rdi.leftEnd() ; k++) {
				
				if (rdi.kind()==rdi.CHANGE) {
					// This we need to diff
					//leftReport.append( "#" );
					seq1.addSequence(leftESC.getItem(k));
					
					// Don't forget our existing prefix mappings!
					PrefixMapping existingPM = leftESC.getItem(k).getPrefixMapping();
					seq1.getPrefixMapping().add(existingPM);
				} else {
					// Does this happen?
					// This just goes straight into the output,
				    formatter.declarePrefixMapping(leftESC.getItem(k).getPrefixMapping());										
					out.append("\n<!-- Adding same II -->\n");
					leftESC.getItem(k).format(formatter);
					out.append("\n<!-- .. Adding same done -->");
				}				
			}
			EventSequence seq2 = new EventSequence();
			for (int k = rdi.rightStart() ; k< rdi.rightEnd() ; k++) {				
				if (rdi.kind()==rdi.CHANGE) {
					// This is the RHS of the diff
					//rightReport.append( "#" );
					seq2.addSequence(rightESC.getItem(k));
					
					// Don't forget our existing prefix mappings!
					PrefixMapping existingPM = rightESC.getItem(k).getPrefixMapping();
					seq2.getPrefixMapping().add(existingPM);
					
				}				
			}
			
			leftIdx = rdi.leftEnd();
			
			// ok, now perform this diff
			//log("performing diff");
			out.append("\n<!-- Differencing -->\n");
			Main.diff(seq1, seq2, formatter, diffxConfig);
			out.append("\n<!-- .. Differencing done -->");
						
		}
		// Tail, if any, goes straight into output
		
		out.append("\n<!-- Adding tail -->\n");
		for (int k = rd[rd.length-1].leftEnd(); k < leftESC.getRangeCount(); k++ ) {
			//leftReport.append( left.getItem(k) );
			leftESC.getItem(k).format(formatter);
		}
		
		// write out parent close element
	    out.append("</" + rootNodeName + ">" );
		
	  }
	
	public static void main(String[] args) throws Exception {
					
		// Result format
		Writer diffxResult = new StringWriter();

		// Run the diff
		try {
			long startTime = System.currentTimeMillis();
			diff( getDocument(new File("1L.xml") ).getDocumentElement(),
					getDocument(new File("1R.xml") ).getDocumentElement(),
					   diffxResult);
				// The signature which takes Reader objects appears to be broken
			diffxResult.close();
			long endTime = System.currentTimeMillis();
			long duration = endTime - startTime;
			//System.out.println(diffxResult.toString());
			System.out.println(duration + "ms");
			System.out.println(diffxResult.toString() );
		} catch (Exception exc) {
			exc.printStackTrace();
			diffxResult = null;
		}
	}
	
	private static Document getDocument(File f) throws Exception {
		
			DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
			dbf.setNamespaceAware(true);
			DocumentBuilder db = dbf.newDocumentBuilder();
			return db.parse(f);		
	}
}
