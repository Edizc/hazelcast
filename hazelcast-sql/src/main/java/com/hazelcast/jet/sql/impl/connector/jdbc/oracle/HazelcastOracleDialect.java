/*
 * Copyright 2023 Hazelcast Inc.
 *
 * Licensed under the Hazelcast Community License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://hazelcast.com/hazelcast-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.jet.sql.impl.connector.jdbc.oracle;

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.sql.SqlDataTypeSpec;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.dialect.OracleSqlDialect;
import org.apache.calcite.sql.SqlAlienSystemTypeNameSpec;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.apache.calcite.sql.type.SqlTypeName;

/**
 * Custom dialect for MSSQL which allows correct unparsing of MSSQL specific operators, like CONCAT
 */
public class HazelcastOracleDialect extends OracleSqlDialect {

    /**
     * Creates a HazelcastMSSQLDialect.
     */
    public HazelcastOracleDialect(Context context) {
        super(context);
    }

    @Override
    public @Nullable SqlNode getCastSpec(RelDataType type) {
        if (type.getSqlTypeName().equals(SqlTypeName.VARCHAR)) {
            return new SqlDataTypeSpec(
                    new SqlAlienSystemTypeNameSpec("VARCHAR(128)", type.getSqlTypeName(), SqlParserPos.ZERO),
                    SqlParserPos.ZERO);
        } else {
            return super.getCastSpec(type);
        }
    }
}
