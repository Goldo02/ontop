package org.semanticweb.ontop.owlrefplatform.core.tboxprocessing;

import org.semanticweb.ontop.ontology.DataPropertyExpression;
import org.semanticweb.ontop.ontology.DataRangeExpression;
import org.semanticweb.ontop.ontology.ObjectPropertyExpression;
import org.semanticweb.ontop.ontology.ClassExpression;
import org.semanticweb.ontop.owlrefplatform.core.dagjgrapht.Equivalences;
import org.semanticweb.ontop.owlrefplatform.core.dagjgrapht.TBoxReasoner;

import org.semanticweb.ontop.owlrefplatform.core.dagjgrapht.Equivalences;
import org.semanticweb.ontop.owlrefplatform.core.dagjgrapht.TBoxReasoner;

public class TBoxTraversal {
	
	public static void traverse(TBoxReasoner reasoner, TBoxTraverseListener listener) {
		
		for (Equivalences<ObjectPropertyExpression> nodes : reasoner.getObjectPropertyDAG()) {
			ObjectPropertyExpression node = nodes.getRepresentative();
			
			for (Equivalences<ObjectPropertyExpression> descendants : reasoner.getObjectPropertyDAG().getSub(nodes)) {
				ObjectPropertyExpression descendant = descendants.getRepresentative();
				listener.onInclusion(descendant, node);
			}
			for (ObjectPropertyExpression equivalent : nodes) {
				if (!equivalent.equals(node)) {
					listener.onInclusion(node, equivalent);
					listener.onInclusion(equivalent, node);
				}
			}
		}
		for (Equivalences<DataPropertyExpression> nodes : reasoner.getDataPropertyDAG()) {
			DataPropertyExpression node = nodes.getRepresentative();
			
			for (Equivalences<DataPropertyExpression> descendants : reasoner.getDataPropertyDAG().getSub(nodes)) {
				DataPropertyExpression descendant = descendants.getRepresentative();

				listener.onInclusion(descendant, node);
			}
			for (DataPropertyExpression equivalent : nodes) {
				if (!equivalent.equals(node)) {
					listener.onInclusion(node, equivalent);
					listener.onInclusion(equivalent, node);
				}
			}
		}
		
		for (Equivalences<ClassExpression> nodes : reasoner.getClassDAG()) {
			ClassExpression node = nodes.getRepresentative();
			
			for (Equivalences<ClassExpression> descendants : reasoner.getClassDAG().getSub(nodes)) {
				ClassExpression descendant = descendants.getRepresentative();
				listener.onInclusion(descendant, node);
					
			}
			for (ClassExpression equivalent : nodes) {
				if (!equivalent.equals(node)) {
					listener.onInclusion(equivalent, node);
					listener.onInclusion(node, equivalent);
				}
			}
		}	
		for (Equivalences<DataRangeExpression> nodes : reasoner.getDataRanges()) {
			DataRangeExpression node = nodes.getRepresentative();
			
			for (Equivalences<DataRangeExpression> descendants : reasoner.getDataRanges().getSub(nodes)) {
				DataRangeExpression descendant = descendants.getRepresentative();
				listener.onInclusion(descendant, node);				
			}
			for (DataRangeExpression equivalent : nodes) {
				if (!equivalent.equals(node)) {
					listener.onInclusion(node, equivalent);
					listener.onInclusion(equivalent, node);
				}
			}
		}	
	}
}
