/**
 * Copyright 2015 Confluent Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **/

package io.confluent.connect.jdbc;

import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.source.SourceRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;

/**
 * <p>
 *   TimestampIncrementingTableQuerier performs incremental loading of data using two mechanisms: a
 *   timestamp column provides monotonically incrementing values that can be used to detect new or
 *   modified rows and a strictly incrementing (e.g. auto increment) column allows detecting new rows
 *   or combined with the timestamp provide a unique identifier for each update to the row.
 * </p>
 * <p>
 *   At least one of the two columns must be specified (or left as "" for the incrementing column
 *   to indicate use of an auto-increment column). If both columns are provided, they are both
 *   used to ensure only new or updated rows are reported and to totally order updates so
 *   recovery can occur no matter when offsets were committed. If only the incrementing fields is
 *   provided, new rows will be detected but not updates. If only the timestamp field is
 *   provided, both new and updated rows will be detected, but stream offsets will not be unique
 *   so failures may cause duplicates or losses.
 * </p>
 */
public class TimestampIncrementingTableQuerier extends TableQuerier {
  private static final Logger log = LoggerFactory.getLogger(TimestampIncrementingTableQuerier.class);

  private static final Calendar UTC_CALENDAR = new GregorianCalendar(TimeZone.getTimeZone("UTC"));

  private String timestampColumn;
  private Long timestampOffset;
  private String incrementingColumn;
  private Long incrementingOffset = null;
  private Integer queryLimit;

  public TimestampIncrementingTableQuerier(QueryMode mode, String name, String topicPrefix,
                                           String timestampColumn, Long timestampOffset,
                                           String incrementingColumn, Long incrementingOffset,
                                           Integer queryLimit) {
    super(mode, name, topicPrefix);
    this.timestampColumn = timestampColumn;
    this.timestampOffset = timestampOffset;
    this.incrementingColumn = incrementingColumn;
    this.incrementingOffset = incrementingOffset;
    this.queryLimit = queryLimit;
  }

  @Override
  protected void createPreparedStatement(Connection db) throws SQLException {
    // Default when unspecified uses an autoincrementing column
    if (incrementingColumn != null && incrementingColumn.isEmpty()) {
      incrementingColumn = JdbcUtils.getAutoincrementColumn(db, name);
    }

    String quoteString = JdbcUtils.getIdentifierQuoteString(db);

    StringBuilder builder = new StringBuilder();

    switch (mode) {
      case TABLE:
        builder.append("SELECT * FROM ");
        builder.append(JdbcUtils.quoteString(name, quoteString));
        break;
      case QUERY:
        builder.append(query);
        break;
      default:
        throw new ConnectException("Unknown mode encountered when preparing query: " + mode.toString());
    }

    if (incrementingColumn != null && timestampColumn != null) {
      // This version combines two possible conditions. The first checks timestamp == last
      // timestamp and incrementing > last incrementing. The timestamp alone would include
      // duplicates, but adding the incrementing condition ensures no duplicates, e.g. you would
      // get only the row with id = 23:
      //  timestamp 1234, id 22 <- last
      //  timestamp 1234, id 23
      // The second check only uses the timestamp >= last timestamp. This covers everything new,
      // even if it is an update of the existing row. If we previously had:
      //  timestamp 1234, id 22 <- last
      // and then these rows were written:
      //  timestamp 1235, id 22
      //  timestamp 1236, id 23
      // We should capture both id = 22 (an update) and id = 23 (a new row)
      builder.append(" WHERE ");
      builder.append(JdbcUtils.quoteString(timestampColumn, quoteString));
      builder.append(" < CURRENT_TIMESTAMP AND ((");
      builder.append(JdbcUtils.quoteString(timestampColumn, quoteString));
      builder.append(" = ? AND ");
      builder.append(JdbcUtils.quoteString(incrementingColumn, quoteString));
      builder.append(" > ?");
      builder.append(") OR ");
      builder.append(JdbcUtils.quoteString(timestampColumn, quoteString));
      builder.append(" > ?)");
      builder.append(" ORDER BY ");
      builder.append(JdbcUtils.quoteString(timestampColumn, quoteString));
      builder.append(",");
      builder.append(JdbcUtils.quoteString(incrementingColumn, quoteString));
      builder.append(" ASC");
    } else if (incrementingColumn != null) {
      builder.append(" WHERE ");
      builder.append(JdbcUtils.quoteString(incrementingColumn, quoteString));
      builder.append(" > ?");
      builder.append(" ORDER BY ");
      builder.append(JdbcUtils.quoteString(incrementingColumn, quoteString));
      builder.append(" ASC");
    } else if (timestampColumn != null) {
      builder.append(" WHERE ");
      builder.append(JdbcUtils.quoteString(timestampColumn, quoteString));
      builder.append(" > ? AND ");
      builder.append(JdbcUtils.quoteString(timestampColumn, quoteString));
      builder.append(" < CURRENT_TIMESTAMP ORDER BY ");
      builder.append(JdbcUtils.quoteString(timestampColumn, quoteString));
      builder.append(" ASC");
    }
    if (queryLimit != 0) {
      builder.append(" LIMIT " + queryLimit);
    }
    String queryString = builder.toString();
    log.debug("{} prepared SQL query: {}", this, queryString);
    stmt = db.prepareStatement(queryString);
  }

  @Override
  protected ResultSet executeQuery() throws SQLException {
    if (incrementingColumn != null && timestampColumn != null) {
      Timestamp ts = new Timestamp(timestampOffset == null ? 0 : timestampOffset);
      stmt.setTimestamp(1, ts, UTC_CALENDAR);
      stmt.setLong(2, (incrementingOffset == null ? -1 : incrementingOffset));
      stmt.setTimestamp(3, ts, UTC_CALENDAR);
    } else if (incrementingColumn != null) {
      stmt.setLong(1, (incrementingOffset == null ? -1 : incrementingOffset));
    } else if (timestampColumn != null) {
      Timestamp ts = new Timestamp(timestampOffset == null ? 0 : timestampOffset);
      stmt.setTimestamp(1, ts, UTC_CALENDAR);
    }
    return stmt.executeQuery();
  }

  @Override
  public SourceRecord extractRecord(String partitionKey) throws SQLException {
    Struct record = DataConverter.convertRecord(schema, resultSet);
    Map<String, Long> offset = new HashMap<>();
    if (incrementingColumn != null) {
      Long id;
      switch (schema.field(incrementingColumn).schema().type()) {
        case INT32:
          id = (long) (Integer) record.get(incrementingColumn);
          break;
        case INT64:
          id = (Long) record.get(incrementingColumn);
          break;
        default:
          throw new ConnectException("Invalid type for incrementing column: "
                                            + schema.field(incrementingColumn).schema().type());
      }

      // If we are only using an incrementing column, then this must be incrementing. If we are also
      // using a timestamp, then we may see updates to older rows.
      assert (incrementingOffset == null || id > incrementingOffset) || timestampColumn != null;
      incrementingOffset = id;

      offset.put(JdbcSourceTask.INCREMENTING_FIELD, id);
    }


    if (timestampColumn != null) {
      Date timestamp = (Date) record.get(timestampColumn);
      assert timestampOffset == null || timestamp.getTime() >= timestampOffset;
      timestampOffset = timestamp.getTime();
      offset.put(JdbcSourceTask.TIMESTAMP_FIELD, timestampOffset);
    }

    // TODO: Key?
    final String topic;
    final Map<String, String> partition;
    switch (mode) {
      case TABLE:
        partition = Collections.singletonMap(JdbcSourceConnectorConstants.TABLE_NAME_KEY, name);
        topic = topicPrefix + name;
        break;
      case QUERY:
        partition = Collections.singletonMap(JdbcSourceConnectorConstants.QUERY_NAME_KEY,
                                             JdbcSourceConnectorConstants.QUERY_NAME_VALUE);
        topic = topicPrefix;
        break;
      default:
        throw new ConnectException("Unexpected query mode: " + mode);
    }
    Object key = null;
    if (!partitionKey.isEmpty()) {
      key = record.get(partitionKey);
    }
    return new SourceRecord(partition, offset, topic, null, null, key, record.schema(), record);
  }

  @Override
  public String toString() {
    return "TimestampIncrementingTableQuerier{" +
           "name='" + name + '\'' +
           ", query='" + query + '\'' +
           ", topicPrefix='" + topicPrefix + '\'' +
           ", timestampColumn='" + timestampColumn + '\'' +
           ", incrementingColumn='" + incrementingColumn + '\'' +
           '}';
  }
}
