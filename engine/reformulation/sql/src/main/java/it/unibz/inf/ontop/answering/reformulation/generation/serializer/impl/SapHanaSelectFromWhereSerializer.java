package it.unibz.inf.ontop.answering.reformulation.generation.serializer.impl;

import com.google.inject.Inject;
import it.unibz.inf.ontop.answering.reformulation.generation.algebra.SelectFromWhereWithModifiers;
import it.unibz.inf.ontop.answering.reformulation.generation.dialect.SQLDialectAdapter;
import it.unibz.inf.ontop.answering.reformulation.generation.serializer.SQLTermSerializer;
import it.unibz.inf.ontop.answering.reformulation.generation.serializer.SelectFromWhereSerializer;
import it.unibz.inf.ontop.dbschema.DBParameters;

public class SapHanaSelectFromWhereSerializer extends DefaultSelectFromWhereSerializer implements SelectFromWhereSerializer {

    @Inject
    private SapHanaSelectFromWhereSerializer(SQLTermSerializer sqlTermSerializer,
                                         SQLDialectAdapter dialectAdapter) {
        super(sqlTermSerializer, dialectAdapter);
    }

    @Override
    public SelectFromWhereSerializer.QuerySerialization serialize(SelectFromWhereWithModifiers
                                                                          selectFromWhere, DBParameters dbParameters) {
        return selectFromWhere.acceptVisitor(
                new DefaultSelectFromWhereSerializer.DefaultRelationVisitingSerializer(dbParameters.getQuotedIDFactory()) {
                    /**
                     * https://help.sap.com/viewer/4fe29514fd584807ac9f2a04f6754767/2.0.03/en-US/20fcf24075191014a89e9dc7b8408b26.html
                     *
                     * LIMIT
                     * Limits the number of records returned and behaves like TOP.
                     * <limit> ::=
                     *  LIMIT <unsigned_integer> [ OFFSET <unsigned_integer> ]
                     *
                     * The following example returns the first 3 records after skipping 5 records.
                     * LIMIT 3 [OFFSET 5]
                     *
                     */

                    // serializeLimit is standard

                    @Override
                    protected String serializeLimitOffset(long limit, long offset) {
                        return String.format("LIMIT %d\nOFFSET %d", limit, offset);
                    }

                    @Override
                    protected String serializeOffset(long offset) {
                        return serializeLimitOffset(Integer.MAX_VALUE, offset);
                    }
                });
    }
}


