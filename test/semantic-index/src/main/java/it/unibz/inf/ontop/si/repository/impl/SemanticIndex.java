package it.unibz.inf.ontop.si.repository.impl;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import it.unibz.inf.ontop.spec.ontology.*;
import it.unibz.inf.ontop.spec.ontology.ClassifiedTBox;
import it.unibz.inf.ontop.utils.ImmutableCollectors;
import org.apache.commons.rdf.api.IRI;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map.Entry;

public class SemanticIndex {

	private final ImmutableMap<OClass, SemanticIndexRange> classRanges;
	private final ImmutableMap<ObjectPropertyExpression, SemanticIndexRange> opRanges;
	private final ImmutableMap<DataPropertyExpression, SemanticIndexRange> dpRanges;
	

	public SemanticIndex(ClassifiedTBox reasoner)  {
		SemanticIndexBuilder builder = new SemanticIndexBuilder();
		classRanges = builder.createSemanticIndex(reasoner.classesDAG()).entrySet().stream()
				// the DAG is reduced to the named part, so the cast below is safe
				.collect(ImmutableCollectors.toMap(e -> (OClass)e.getKey(), Entry::getValue));
		opRanges = ImmutableMap.copyOf(builder.createSemanticIndex(reasoner.objectPropertiesDAG()));
		dpRanges = ImmutableMap.copyOf(builder.createSemanticIndex(reasoner.dataPropertiesDAG()));
	}


	public ImmutableSet<Entry<OClass, SemanticIndexRange>> getIndexedClasses() {
		return classRanges.entrySet();
	}
	public ImmutableSet<Entry<ObjectPropertyExpression, SemanticIndexRange>> getIndexedObjectProperties() {
		return opRanges.entrySet();
	}
	public ImmutableSet<Entry<DataPropertyExpression, SemanticIndexRange>> getIndexedDataProperties() {
		return dpRanges.entrySet();
	}

	
	public SemanticIndexRange getRange(OClass d) {
		return classRanges.get(d);
	}
	public SemanticIndexRange getRange(ObjectPropertyExpression d) {
		return opRanges.get(d);
	}
	public SemanticIndexRange getRange(DataPropertyExpression d) {
		return dpRanges.get(d);
	}


	private final static RDBMSSIRepositoryManager.TableDescription indexTable = new RDBMSSIRepositoryManager.TableDescription("IDX",
			ImmutableMap.of("URI", "VARCHAR(400)",
					"IDX", "INTEGER",
					"ENTITY_TYPE", "INTEGER"), "*");

	private final static RDBMSSIRepositoryManager.TableDescription intervalTable = new RDBMSSIRepositoryManager.TableDescription("IDXINTERVAL",
			ImmutableMap.of("URI", "VARCHAR(400)",
					"IDX_FROM", "INTEGER",
					"IDX_TO", "INTEGER",
					"ENTITY_TYPE", "INTEGER"), "*");


	public void init(java.sql.Statement st) throws SQLException {
		st.addBatch(indexTable.getCREATE());
		st.addBatch(intervalTable.getCREATE());
	}

	public final static int CLASS_TYPE = 1;
	public final static int ROLE_TYPE = 2;


	public void store(Connection conn) throws SQLException {
		// dropping previous metadata
		try (Statement st = conn.createStatement()) {
			st.executeUpdate("DELETE FROM " + indexTable.tableName);
			st.executeUpdate("DELETE FROM " + intervalTable.tableName);
		}

		try (PreparedStatement stm = conn.prepareStatement(indexTable.getINSERT("?, ?, ?"))) {
			for (Entry<OClass, SemanticIndexRange> e : getIndexedClasses())
				insertIndexData(stm, e.getKey().getIRI(), e.getValue(), CLASS_TYPE);

			for (Entry<ObjectPropertyExpression, SemanticIndexRange> e : getIndexedObjectProperties())
				insertIndexData(stm, e.getKey().getIRI(), e.getValue(), ROLE_TYPE);

			for (Entry<DataPropertyExpression, SemanticIndexRange> e : getIndexedDataProperties())
				insertIndexData(stm, e.getKey().getIRI(), e.getValue(), ROLE_TYPE);

			stm.executeBatch();
		}

		try (PreparedStatement stm = conn.prepareStatement(intervalTable.getINSERT("?, ?, ?, ?"))) {
			for (Entry<OClass, SemanticIndexRange> e : getIndexedClasses())
				insertIntervalMetadata(stm, e.getKey().getIRI(), e.getValue(), CLASS_TYPE);

			for (Entry<ObjectPropertyExpression, SemanticIndexRange> e : getIndexedObjectProperties())
				insertIntervalMetadata(stm, e.getKey().getIRI(), e.getValue(), ROLE_TYPE);

			for (Entry<DataPropertyExpression, SemanticIndexRange> e : getIndexedDataProperties())
				insertIntervalMetadata(stm, e.getKey().getIRI(), e.getValue(), ROLE_TYPE);

			stm.executeBatch();
		}
	}

	private void insertIntervalMetadata(PreparedStatement stm, IRI iri, SemanticIndexRange range, int type) throws SQLException {
		for (Interval it : range.getIntervals()) {
			stm.setString(1, iri.getIRIString());
			stm.setInt(2, it.getStart());
			stm.setInt(3, it.getEnd());
			stm.setInt(4, type);
			stm.addBatch();
		}
	}

	private void insertIndexData(PreparedStatement stm, IRI iri, SemanticIndexRange range, int type) throws SQLException {
		stm.setString(1, iri.getIRIString());
		stm.setInt(2, range.getIndex());
		stm.setInt(3, type);
		stm.addBatch();
	}
}
