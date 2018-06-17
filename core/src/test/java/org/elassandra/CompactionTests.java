/*
 * Copyright (c) 2017 Strapdata (http://www.strapdata.com)
 * Contains some code from Elasticsearch (http://www.elastic.co)
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.elassandra;

import com.codahale.metrics.Gauge;
import com.codahale.metrics.Metric;
import com.codahale.metrics.MetricFilter;

import org.apache.cassandra.cql3.UntypedResultSet;
import org.apache.cassandra.db.ConsistencyLevel;
import org.apache.cassandra.metrics.CassandraMetricsRegistry;
import org.apache.cassandra.service.StorageService;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.test.ESSingleNodeTestCase;
import org.junit.Test;

import java.util.Map;

import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.hamcrest.Matchers.equalTo;

/**
 * Elassandra SSTable compactions tests.
 * @author vroyer
 *
 */
public class CompactionTests extends ESSingleNodeTestCase {
    
    // gradle :core:test -Dtests.seed=C2C04213660E4546 -Dtests.class=org.elassandra.CompactionTests -Dtests.method="expiredTtlColumnCompactionTest" -Dtests.security.manager=false -Dtests.locale=zh -Dtests.timezone=Canada/Eastern
    @Test
    public void basicCompactionTest() throws Exception {
        createIndex("test");
        ensureGreen("test");
        
        process(ConsistencyLevel.ONE,"CREATE TABLE IF NOT EXISTS test.t1 ( a int, b text, primary key (a) ) WITH "+
                "compaction = {'class': 'org.apache.cassandra.db.compaction.SizeTieredCompactionStrategy', 'max_threshold': '32', 'min_threshold': '4'}");
        process(ConsistencyLevel.ONE,"CREATE TABLE IF NOT EXISTS test.t2 ( a int, b text, c int, primary key ((a),b) ) WITH "+
                "compaction = {'class': 'org.apache.cassandra.db.compaction.SizeTieredCompactionStrategy', 'max_threshold': '32', 'min_threshold': '4'}");
        XContentBuilder mappingt1 = XContentFactory.jsonBuilder().startObject().startObject("t1").field("discover",".*").endObject().endObject();
        XContentBuilder mappingt2 = XContentFactory.jsonBuilder().startObject().startObject("t2").field("discover",".*").endObject().endObject();
        
        assertAcked(client().admin().indices().preparePutMapping("test").setType("t1").setSource(mappingt1).get());
        assertAcked(client().admin().indices().preparePutMapping("test").setType("t2").setSource(mappingt2).get());
        
        Map<String, Gauge> gaugest1 = CassandraMetricsRegistry.Metrics.getGauges(new MetricFilter() {
            @Override
            public boolean matches(String name, Metric metric) {
                return name.endsWith("t1");
            }
        });
        Map<String, Gauge> gaugest2 = CassandraMetricsRegistry.Metrics.getGauges(new MetricFilter() {
            @Override
            public boolean matches(String name, Metric metric) {
                return name.endsWith("t2");
            }
        });
        
        int i=0;
        for(int j=0 ; j < 1000; j++) {
            i++;
            process(ConsistencyLevel.ONE,"insert into test.t1 (a,b) VALUES (?,?)", i, "x"+i);
            process(ConsistencyLevel.ONE,"insert into test.t2 (a,b,c) VALUES (?,?,?)", i, "x", i);
            process(ConsistencyLevel.ONE,"insert into test.t2 (a,b,c) VALUES (?,?,?)", i, "y", i);
        }
        assertThat(client().prepareSearch().setIndices("test").setTypes("t1").setQuery(QueryBuilders.matchAllQuery()).get().getHits().getTotalHits(), equalTo(1000L));
        assertThat(client().prepareSearch().setIndices("test").setTypes("t2").setQuery(QueryBuilders.matchAllQuery()).get().getHits().getTotalHits(), equalTo(2*1000L));
        StorageService.instance.forceKeyspaceFlush("test","t1");
        StorageService.instance.forceKeyspaceFlush("test","t2");
        
        for(String s:gaugest1.keySet())
            System.out.println(s+"="+gaugest1.get(s).getValue());
        assertThat(gaugest1.get("org.apache.cassandra.metrics.Table.LiveSSTableCount.test.t1").getValue(), equalTo(1));
        assertThat(gaugest2.get("org.apache.cassandra.metrics.Table.LiveSSTableCount.test.t2").getValue(), equalTo(1));
        
        for(int j=0 ; j < 1000; j++) {
            i++;
            process(ConsistencyLevel.ONE,"insert into test.t1 (a,b) VALUES (?,?)", i, "x"+i);
            process(ConsistencyLevel.ONE,"insert into test.t2 (a,b,c) VALUES (?,?,?)", i, "x", i);
            process(ConsistencyLevel.ONE,"insert into test.t2 (a,b,c) VALUES (?,?,?)", i, "y", i);
        }
        assertThat(client().prepareSearch().setIndices("test").setTypes("t1").setQuery(QueryBuilders.matchAllQuery()).get().getHits().getTotalHits(), equalTo(2000L));
        assertThat(client().prepareSearch().setIndices("test").setTypes("t2").setQuery(QueryBuilders.matchAllQuery()).get().getHits().getTotalHits(), equalTo(2*2000L));
        StorageService.instance.forceKeyspaceFlush("test","t1");
        StorageService.instance.forceKeyspaceFlush("test","t2");
        Thread.sleep(2000);
        assertThat(gaugest1.get("org.apache.cassandra.metrics.Table.LiveSSTableCount.test.t1").getValue(), equalTo(2));
        assertThat(gaugest2.get("org.apache.cassandra.metrics.Table.LiveSSTableCount.test.t2").getValue(), equalTo(2));
        
        for(int j=0 ; j < 1000; j++) {
            i++;
            process(ConsistencyLevel.ONE,"insert into test.t1 (a,b) VALUES (?,?)", i, "x"+i);
            process(ConsistencyLevel.ONE,"insert into test.t2 (a,b,c) VALUES (?,?,?)", i, "x", i);
            process(ConsistencyLevel.ONE,"insert into test.t2 (a,b,c) VALUES (?,?,?)", i, "y", i);
        }
        assertThat(client().prepareSearch().setIndices("test").setTypes("t1").setQuery(QueryBuilders.matchAllQuery()).get().getHits().getTotalHits(), equalTo(3000L));
        assertThat(client().prepareSearch().setIndices("test").setTypes("t2").setQuery(QueryBuilders.matchAllQuery()).get().getHits().getTotalHits(), equalTo(2*3000L));
        StorageService.instance.forceKeyspaceFlush("test");
        assertThat(gaugest1.get("org.apache.cassandra.metrics.Table.LiveSSTableCount.test.t1").getValue(), equalTo(3));
        assertThat(gaugest2.get("org.apache.cassandra.metrics.Table.LiveSSTableCount.test.t2").getValue(), equalTo(3));
        
        // force compaction
        StorageService.instance.forceKeyspaceCompaction(true, "test");
        assertThat(client().prepareSearch().setIndices("test").setTypes("t1").setQuery(QueryBuilders.matchAllQuery()).get().getHits().getTotalHits(), equalTo(3000L));
        assertThat(client().prepareSearch().setIndices("test").setTypes("t2").setQuery(QueryBuilders.matchAllQuery()).get().getHits().getTotalHits(), equalTo(2*3000L));
        assertThat(gaugest1.get("org.apache.cassandra.metrics.Table.LiveSSTableCount.test.t1").getValue(), equalTo(1));
        assertThat(gaugest2.get("org.apache.cassandra.metrics.Table.LiveSSTableCount.test.t2").getValue(), equalTo(1));
        
        for(String s:gaugest1.keySet())
            System.out.println(s+"="+gaugest1.get(s).getValue());
        
        // overwrite 1000 docs
        for(int j=0 ; j < 1000; j++) {
            process(ConsistencyLevel.ONE,"insert into test.t1 (a,b) VALUES (?,?)", 1000+j, "y");
            process(ConsistencyLevel.ONE,"insert into test.t2 (a,b,c) VALUES (?,?,?)", 1000+j, "x", i);
            process(ConsistencyLevel.ONE,"insert into test.t2 (a,b,c) VALUES (?,?,?)",1000+j, "y", i);
        }
        assertThat(client().prepareSearch().setIndices("test").setTypes("t1").setQuery(QueryBuilders.matchAllQuery()).get().getHits().getTotalHits(), equalTo(3000L));
        assertThat(client().prepareSearch().setIndices("test").setTypes("t1").setQuery(QueryBuilders.queryStringQuery("b:y")).get().getHits().getTotalHits(), equalTo(1000L));
        
        assertThat(client().prepareSearch().setIndices("test").setTypes("t2").setQuery(QueryBuilders.matchAllQuery()).get().getHits().getTotalHits(), equalTo(2*3000L));
        assertThat(client().prepareSearch().setIndices("test").setTypes("t2").setQuery(QueryBuilders.queryStringQuery("b:y")).get().getHits().getTotalHits(), equalTo(3000L));
        
        StorageService.instance.forceKeyspaceFlush("test");
        assertThat(gaugest1.get("org.apache.cassandra.metrics.Table.LiveSSTableCount.test.t1").getValue(), equalTo(2));
        assertThat(gaugest2.get("org.apache.cassandra.metrics.Table.LiveSSTableCount.test.t2").getValue(), equalTo(2));
        StorageService.instance.forceKeyspaceCompaction(true, "test");
        assertThat(gaugest1.get("org.apache.cassandra.metrics.Table.LiveSSTableCount.test.t1").getValue(), equalTo(1));
        assertThat(gaugest2.get("org.apache.cassandra.metrics.Table.LiveSSTableCount.test.t2").getValue(), equalTo(1));
        
        assertThat(client().prepareSearch().setIndices("test").setTypes("t1").setQuery(QueryBuilders.matchAllQuery()).get().getHits().getTotalHits(), equalTo(3000L));
        assertThat(client().prepareSearch().setIndices("test").setTypes("t1").setQuery(QueryBuilders.queryStringQuery("b:y")).get().getHits().getTotalHits(), equalTo(1000L));
        
        assertThat(client().prepareSearch().setIndices("test").setTypes("t2").setQuery(QueryBuilders.matchAllQuery()).get().getHits().getTotalHits(), equalTo(2*3000L));
        assertThat(client().prepareSearch().setIndices("test").setTypes("t2").setQuery(QueryBuilders.queryStringQuery("b:y")).get().getHits().getTotalHits(), equalTo(3000L));
        
        // remove 1000 docs
        for(int j=0 ; j < 1000; j++) {
            process(ConsistencyLevel.ONE,"delete from test.t1 WHERE a = ?", 1000+j);
            process(ConsistencyLevel.ONE,"delete from test.t2 WHERE a = ? and b = ?", 1000+j, "x");
            process(ConsistencyLevel.ONE,"delete from test.t2 WHERE a = ? and b = ?", 1000+j, "y");
        }
        assertThat(client().prepareSearch().setIndices("test").setTypes("t1").setQuery(QueryBuilders.matchAllQuery()).get().getHits().getTotalHits(), equalTo(2000L));
        assertThat(client().prepareSearch().setIndices("test").setTypes("t1").setQuery(QueryBuilders.queryStringQuery("b:y")).get().getHits().getTotalHits(), equalTo(0L));

        assertThat(client().prepareSearch().setIndices("test").setTypes("t2").setQuery(QueryBuilders.matchAllQuery()).get().getHits().getTotalHits(), equalTo(2*2000L));
        assertThat(client().prepareSearch().setIndices("test").setTypes("t2").setQuery(QueryBuilders.queryStringQuery("b:y")).get().getHits().getTotalHits(), equalTo(2000L));

        StorageService.instance.forceKeyspaceFlush("test");
        StorageService.instance.forceKeyspaceCompaction(true, "test");
        assertThat(gaugest1.get("org.apache.cassandra.metrics.Table.LiveSSTableCount.test.t1").getValue(), equalTo(1));
        assertThat(gaugest2.get("org.apache.cassandra.metrics.Table.LiveSSTableCount.test.t2").getValue(), equalTo(1));
        
        assertThat(client().prepareSearch().setIndices("test").setTypes("t1").setQuery(QueryBuilders.matchAllQuery()).get().getHits().getTotalHits(), equalTo(2000L));
        assertThat(client().prepareSearch().setIndices("test").setTypes("t1").setQuery(QueryBuilders.queryStringQuery("b:y")).get().getHits().getTotalHits(), equalTo(0L));
        
        assertThat(client().prepareSearch().setIndices("test").setTypes("t2").setQuery(QueryBuilders.matchAllQuery()).get().getHits().getTotalHits(), equalTo(2*2000L));
        assertThat(client().prepareSearch().setIndices("test").setTypes("t2").setQuery(QueryBuilders.queryStringQuery("b:y")).get().getHits().getTotalHits(), equalTo(2000L));
    }
    
    @Test
    public void expiredTtlCompactionTest() throws Exception {
        createIndex("test", Settings.builder().put(IndexMetaData.SETTING_INDEX_ON_COMPACTION, true).build());
        ensureGreen("test");
        
        long N = 10;
        
        process(ConsistencyLevel.ONE,"CREATE TABLE IF NOT EXISTS test.t1 ( a int,b text, primary key (a) ) WITH "+
                "gc_grace_seconds = 15 " +
                " AND compaction = {'class': 'org.apache.cassandra.db.compaction.SizeTieredCompactionStrategy', 'max_threshold': '32', 'min_threshold': '4'}");
        assertAcked(client().admin().indices().preparePutMapping("test").setType("t1").setSource("{ \"t1\" : { \"discover\" : \".*\", \"_meta\": { \"index_on_compaction\":true } }}").get());
        
        Map<String, Gauge> gauges = CassandraMetricsRegistry.Metrics.getGauges(new MetricFilter() {
            @Override
            public boolean matches(String name, Metric metric) {
                return name.endsWith("t1");
            }
        });
        
        int i=0;
        for(int j=0 ; j < N; j++) {
            i++;
            process(ConsistencyLevel.ONE,"insert into test.t1 (a,b) VALUES (?,?)", i, "x"+i);
        }
        assertThat(client().prepareSearch().setIndices("test").setTypes("t1").setQuery(QueryBuilders.matchAllQuery()).get().getHits().getTotalHits(), equalTo(N));
        StorageService.instance.forceKeyspaceFlush("test","t1");
        
        for(String s:gauges.keySet())
            System.out.println(s+"="+gauges.get(s).getValue());
        assertThat(gauges.get("org.apache.cassandra.metrics.Table.LiveSSTableCount.test.t1").getValue(), equalTo(1));
        
        for(int j=0 ; j < N; j++) {
            i++;
            process(ConsistencyLevel.ONE,"insert into test.t1 (a,b) VALUES (?,?) USING TTL 15", i, "y");
        }
        assertThat(client().prepareSearch().setIndices("test").setTypes("t1").setQuery(QueryBuilders.matchAllQuery()).get().getHits().getTotalHits(), equalTo(2*N));
        assertThat(client().prepareSearch().setIndices("test").setTypes("t1").setQuery(QueryBuilders.queryStringQuery("b:y")).get().getHits().getTotalHits(), equalTo(N));
        StorageService.instance.forceKeyspaceFlush("test","t1");
        Thread.sleep(2000);
        assertThat(gauges.get("org.apache.cassandra.metrics.Table.LiveSSTableCount.test.t1").getValue(), equalTo(2));
        
        for(int j=0 ; j < N; j++) {
            i++;
            process(ConsistencyLevel.ONE,"insert into test.t1 (a,b) VALUES (?,?)", i, "x"+i);
        }
        assertThat(client().prepareSearch().setIndices("test").setTypes("t1").setQuery(QueryBuilders.matchAllQuery()).get().getHits().getTotalHits(), equalTo(3*N));
        assertThat(client().prepareSearch().setIndices("test").setTypes("t1").setQuery(QueryBuilders.queryStringQuery("b:y")).get().getHits().getTotalHits(), equalTo(N));
        StorageService.instance.forceKeyspaceFlush("test");
        assertThat(gauges.get("org.apache.cassandra.metrics.Table.LiveSSTableCount.test.t1").getValue(), equalTo(3));
        
        // force compaction
        StorageService.instance.forceKeyspaceCompaction(true, "test");
        assertThat(client().prepareSearch().setIndices("test").setTypes("t1").setQuery(QueryBuilders.matchAllQuery()).get().getHits().getTotalHits(), equalTo(3*N));
        assertThat(client().prepareSearch().setIndices("test").setTypes("t1").setQuery(QueryBuilders.queryStringQuery("b:y")).get().getHits().getTotalHits(), equalTo(N));
        assertThat(gauges.get("org.apache.cassandra.metrics.Table.LiveSSTableCount.test.t1").getValue(), equalTo(1));
        
        for(String s:gauges.keySet())
            System.out.println(s+"="+gauges.get(s).getValue());
       
        
        Thread.sleep(15*1000);  // wait TTL expiration
        Thread.sleep(20*1000);  // wait gc_grace_seconds expiration
        assertThat(client().prepareSearch().setIndices("test").setTypes("t1").setQuery(QueryBuilders.matchAllQuery()).get().getHits().getTotalHits(), equalTo(3*N));
        assertThat(client().prepareSearch().setIndices("test").setTypes("t1").setQuery(QueryBuilders.queryStringQuery("b:y")).get().getHits().getTotalHits(), equalTo(N));

        StorageService.instance.forceKeyspaceFlush("test");
        StorageService.instance.forceKeyspaceCompaction(true, "test");
        assertThat(gauges.get("org.apache.cassandra.metrics.Table.LiveSSTableCount.test.t1").getValue(), equalTo(1));
        
        UntypedResultSet rs = process(ConsistencyLevel.ONE,"SELECT * FROM test.t1");
        System.out.println("t1.count = "+rs.size());
        assertThat(client().prepareSearch().setIndices("test").setTypes("t1").setQuery(QueryBuilders.matchAllQuery()).get().getHits().getTotalHits(), equalTo(2*N));
        assertThat(client().prepareSearch().setIndices("test").setTypes("t1").setQuery(QueryBuilders.queryStringQuery("b:y")).get().getHits().getTotalHits(), equalTo(0L));
    }
    
    // gradle :core:test -Dtests.seed=C2C04213660E4546 -Dtests.class=org.elassandra.CompositeTests -Dtests.method="testReadBeforeWrite" -Dtests.security.manager=false -Dtests.locale=zh-TW -Dtests.timezone=Pacific/Pitcairn
    @Test
    public void expiredTtlColumnCompactionTest() throws Exception {
        createIndex("test", Settings.builder().put(IndexMetaData.SETTING_INDEX_ON_COMPACTION, true).build());
        ensureGreen("test");
        
        long N = 10;
        
        process(ConsistencyLevel.ONE,"CREATE TABLE IF NOT EXISTS test.t1 ( a int,b text, c text, primary key (a) ) WITH "+
                "gc_grace_seconds = 15 " +
                " AND compaction = {'class': 'org.apache.cassandra.db.compaction.SizeTieredCompactionStrategy', 'max_threshold': '32', 'min_threshold': '4'}");
        assertAcked(client().admin().indices().preparePutMapping("test").setType("t1").setSource("{ \"t1\" : { \"discover\" : \".*\" }}").get());
        
        Map<String, Gauge> gauges = CassandraMetricsRegistry.Metrics.getGauges(new MetricFilter() {
            @Override
            public boolean matches(String name, Metric metric) {
                return name.endsWith("t1");
            }
        });
        
        int i=0;
        for(int j=0 ; j < N; j++) {
            i++;
            process(ConsistencyLevel.ONE,"insert into test.t1 (a,b,c) VALUES (?,?,?)", i, "b"+i, "c"+i);
        }
        assertThat(client().prepareSearch().setIndices("test").setTypes("t1").setQuery(QueryBuilders.matchAllQuery()).get().getHits().getTotalHits(), equalTo(N));
        StorageService.instance.forceKeyspaceFlush("test","t1");
        
        for(String s:gauges.keySet())
            System.out.println(s+"="+gauges.get(s).getValue());
        assertThat(gauges.get("org.apache.cassandra.metrics.Table.LiveSSTableCount.test.t1").getValue(), equalTo(1));
        
        for(int j=0 ; j < N; j++) {
            i++;
            process(ConsistencyLevel.ONE,"insert into test.t1 (a,c) VALUES (?,?) ", i, "c"+i);
            process(ConsistencyLevel.ONE,"insert into test.t1 (a,b) VALUES (?,?) USING TTL 15", i, "b"+i);
        }
        assertThat(client().prepareSearch().setIndices("test").setTypes("t1").setQuery(QueryBuilders.matchAllQuery()).get().getHits().getTotalHits(), equalTo(2*N));
        assertThat(client().prepareSearch().setIndices("test").setTypes("t1").setQuery(QueryBuilders.wildcardQuery("b", "*")).get().getHits().getTotalHits(), equalTo(2*N));
        StorageService.instance.forceKeyspaceFlush("test","t1");
        assertThat(gauges.get("org.apache.cassandra.metrics.Table.LiveSSTableCount.test.t1").getValue(), equalTo(2));
        
        // force compaction
        StorageService.instance.forceKeyspaceCompaction(true, "test");
        assertThat(client().prepareSearch().setIndices("test").setTypes("t1").setQuery(QueryBuilders.matchAllQuery()).get().getHits().getTotalHits(), equalTo(2*N));
        assertThat(client().prepareSearch().setIndices("test").setTypes("t1").setQuery(QueryBuilders.wildcardQuery("b","*")).get().getHits().getTotalHits(), equalTo(2*N));
        assertThat(gauges.get("org.apache.cassandra.metrics.Table.LiveSSTableCount.test.t1").getValue(), equalTo(1));
       
        Thread.sleep(15*1000);  // wait TTL expiration
        Thread.sleep(20*1000);  // wait gc_grace_seconds expiration
        assertThat(client().prepareSearch().setIndices("test").setTypes("t1").setQuery(QueryBuilders.matchAllQuery()).get().getHits().getTotalHits(), equalTo(2*N));
        assertThat(client().prepareSearch().setIndices("test").setTypes("t1").setQuery(QueryBuilders.wildcardQuery("c","*")).get().getHits().getTotalHits(), equalTo(2*N));
        assertThat(client().prepareSearch().setIndices("test").setTypes("t1").setQuery(QueryBuilders.wildcardQuery("b","*")).get().getHits().getTotalHits(), equalTo(2*N));

        StorageService.instance.forceKeyspaceFlush("test");
        StorageService.instance.forceKeyspaceCompaction(true, "test");
        assertThat(gauges.get("org.apache.cassandra.metrics.Table.LiveSSTableCount.test.t1").getValue(), equalTo(1));
        
        UntypedResultSet rs = process(ConsistencyLevel.ONE,"SELECT * FROM test.t1");
        System.out.println("t1.count = "+rs.size());
        assertThat(client().prepareSearch().setIndices("test").setTypes("t1").setQuery(QueryBuilders.matchAllQuery()).get().getHits().getTotalHits(), equalTo(2*N));
        assertThat(client().prepareSearch().setIndices("test").setTypes("t1").setQuery(QueryBuilders.wildcardQuery("c","*")).get().getHits().getTotalHits(), equalTo(2*N));
        assertThat(client().prepareSearch().setIndices("test").setTypes("t1").setQuery(QueryBuilders.wildcardQuery("b","*")).get().getHits().getTotalHits(), equalTo(N));
    }
}
