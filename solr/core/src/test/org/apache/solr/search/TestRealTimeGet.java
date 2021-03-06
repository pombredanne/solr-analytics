/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.search;


import org.apache.lucene.util.LuceneTestCase.Slow;
import org.apache.noggit.ObjectBuilder;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.util.TestHarness;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.apache.solr.core.SolrCore.verbose;
import static org.apache.solr.update.processor.DistributingUpdateProcessorFactory.DISTRIB_UPDATE_PARAM;

public class TestRealTimeGet extends TestRTGBase {

  @BeforeClass
  public static void beforeClass() throws Exception {
    initCore("solrconfig-tlog.xml","schema15.xml");
  }


  @Test
  public void testGetRealtime() throws Exception {
    clearIndex();
    assertU(commit());

    assertU(adoc("id","1"));
    assertJQ(req("q","id:1")
        ,"/response/numFound==0"
    );
    assertJQ(req("qt","/get", "id","1", "fl","id")
        ,"=={'doc':{'id':'1'}}"
    );
    assertJQ(req("qt","/get","ids","1", "fl","id")
        ,"=={" +
        "  'response':{'numFound':1,'start':0,'docs':[" +
        "      {" +
        "        'id':'1'}]" +
        "  }}}"
    );

    assertU(commit());

    assertJQ(req("q","id:1")
        ,"/response/numFound==1"
    );
    assertJQ(req("qt","/get","id","1", "fl","id")
        ,"=={'doc':{'id':'1'}}"
    );
    assertJQ(req("qt","/get","ids","1", "fl","id")
        ,"=={" +
        "  'response':{'numFound':1,'start':0,'docs':[" +
        "      {" +
        "        'id':'1'}]" +
        "  }}}"
    );

    assertU(delI("1"));

    assertJQ(req("q","id:1")
        ,"/response/numFound==1"
    );
    assertJQ(req("qt","/get","id","1")
        ,"=={'doc':null}"
    );
    assertJQ(req("qt","/get","ids","1")
        ,"=={'response':{'numFound':0,'start':0,'docs':[]}}"
    );


    assertU(adoc("id","10"));
    assertU(adoc("id","11"));
    assertJQ(req("qt","/get","id","10", "fl","id")
        ,"=={'doc':{'id':'10'}}"
    );
    assertU(delQ("id:10 abcdef"));
    assertJQ(req("qt","/get","id","10")
        ,"=={'doc':null}"
    );
    assertJQ(req("qt","/get","id","11", "fl","id")
        ,"=={'doc':{'id':'11'}}"
    );


  }


  @Test
  public void testVersions() throws Exception {
    clearIndex();
    assertU(commit());

    long version = addAndGetVersion(sdoc("id","1") , null);

    assertJQ(req("q","id:1")
        ,"/response/numFound==0"
    );

    // test version is there from rtg
    assertJQ(req("qt","/get","id","1")
        ,"=={'doc':{'id':'1','_version_':" + version + "}}"
    );

    // test version is there from the index
    assertU(commit());
    assertJQ(req("qt","/get","id","1")
        ,"=={'doc':{'id':'1','_version_':" + version + "}}"
    );

    // simulate an update from the leader
    version += 10;
    updateJ(jsonAdd(sdoc("id","1", "_version_",Long.toString(version))), params(DISTRIB_UPDATE_PARAM,FROM_LEADER));

    // test version is there from rtg
    assertJQ(req("qt","/get","id","1")
        ,"=={'doc':{'id':'1','_version_':" + version + "}}"
    );

    // simulate reordering: test that a version less than that does not take affect
    updateJ(jsonAdd(sdoc("id","1", "_version_",Long.toString(version - 1))), params(DISTRIB_UPDATE_PARAM,FROM_LEADER));

    // test that version hasn't changed
    assertJQ(req("qt","/get","id","1")
        ,"=={'doc':{'id':'1','_version_':" + version + "}}"
    );

    // simulate reordering: test that a delete w/ version less than that does not take affect
    // TODO: also allow passing version on delete instead of on URL?
    updateJ(jsonDelId("1"), params(DISTRIB_UPDATE_PARAM,FROM_LEADER, "_version_",Long.toString(version - 1)));

    // test that version hasn't changed
    assertJQ(req("qt","/get","id","1")
        ,"=={'doc':{'id':'1','_version_':" + version + "}}"
    );

    // make sure reordering detection also works after a commit
    assertU(commit());

    // simulate reordering: test that a version less than that does not take affect
    updateJ(jsonAdd(sdoc("id","1", "_version_",Long.toString(version - 1))), params(DISTRIB_UPDATE_PARAM,FROM_LEADER));

    // test that version hasn't changed
    assertJQ(req("qt","/get","id","1")
        ,"=={'doc':{'id':'1','_version_':" + version + "}}"
    );

    // simulate reordering: test that a delete w/ version less than that does not take affect
    updateJ(jsonDelId("1"), params(DISTRIB_UPDATE_PARAM,FROM_LEADER, "_version_",Long.toString(version - 1)));

    // test that version hasn't changed
    assertJQ(req("qt","/get","id","1")
        ,"=={'doc':{'id':'1','_version_':" + version + "}}"
    );

    // now simulate a normal delete from the leader
    version += 5;
    updateJ(jsonDelId("1"), params(DISTRIB_UPDATE_PARAM,FROM_LEADER, "_version_",Long.toString(version)));

    // make sure a reordered add doesn't take affect.
    updateJ(jsonAdd(sdoc("id","1", "_version_",Long.toString(version - 1))), params(DISTRIB_UPDATE_PARAM,FROM_LEADER));

    // test that it's still deleted
    assertJQ(req("qt","/get","id","1")
        ,"=={'doc':null}"
    );

    // test that we can remember the version of a delete after a commit
    assertU(commit());

    // make sure a reordered add doesn't take affect.
    long version2 = deleteByQueryAndGetVersion("id:2", null);

    // test that it's still deleted
    assertJQ(req("qt","/get","id","1")
        ,"=={'doc':null}"
    );

    version = addAndGetVersion(sdoc("id","2"), null);
    version2 = deleteByQueryAndGetVersion("id:2", null);
    assertTrue(Math.abs(version2) > version );

    // test that it's deleted
    assertJQ(req("qt","/get","id","2")
        ,"=={'doc':null}");


    version2 = Math.abs(version2) + 1000;
    updateJ(jsonAdd(sdoc("id","3", "_version_",Long.toString(version2+100))), params(DISTRIB_UPDATE_PARAM,FROM_LEADER));
    updateJ(jsonAdd(sdoc("id","4", "_version_",Long.toString(version2+200))), params(DISTRIB_UPDATE_PARAM,FROM_LEADER));

    // this should only affect id:3 so far
    deleteByQueryAndGetVersion("id:(3 4 5 6)", params(DISTRIB_UPDATE_PARAM,FROM_LEADER, "_version_",Long.toString(-(version2+150))) );

    assertJQ(req("qt","/get","id","3"),"=={'doc':null}");
    assertJQ(req("qt","/get","id","4", "fl","id"),"=={'doc':{'id':'4'}}");

    updateJ(jsonAdd(sdoc("id","5", "_version_",Long.toString(version2+201))), params(DISTRIB_UPDATE_PARAM,FROM_LEADER));
    updateJ(jsonAdd(sdoc("id","6", "_version_",Long.toString(version2+101))), params(DISTRIB_UPDATE_PARAM,FROM_LEADER));

   // the DBQ should also have caused id:6 to be removed
    assertJQ(req("qt","/get","id","5", "fl","id"),"=={'doc':{'id':'5'}}");
    assertJQ(req("qt","/get","id","6"),"=={'doc':null}");

    assertU(commit());

  }

  @Test
  public void testOptimisticLocking() throws Exception {
    clearIndex();
    assertU(commit());

    long version = addAndGetVersion(sdoc("id","1") , null);
    long version2;

    try {
      // try version added directly on doc
      version2 = addAndGetVersion(sdoc("id","1", "_version_", Long.toString(version-1)), null);
      fail();
    } catch (SolrException se) {
      assertEquals(409, se.code());
    }

    try {
      // try version added as a parameter on the request
      version2 = addAndGetVersion(sdoc("id","1"), params("_version_", Long.toString(version-1)));
      fail();
    } catch (SolrException se) {
      assertEquals(409, se.code());
    }

    try {
      // try an add specifying a negative version
      version2 = addAndGetVersion(sdoc("id","1"), params("_version_", Long.toString(-version)));
      fail();
    } catch (SolrException se) {
      assertEquals(409, se.code());
    }

    try {
      // try an add with a greater version
      version2 = addAndGetVersion(sdoc("id","1"), params("_version_", Long.toString(version+random().nextInt(1000)+1)));
      fail();
    } catch (SolrException se) {
      assertEquals(409, se.code());
    }

    //
    // deletes
    //

    try {
      // try a delete with version on the request
      version2 = deleteAndGetVersion("1", params("_version_", Long.toString(version-1)));
      fail();
    } catch (SolrException se) {
      assertEquals(409, se.code());
    }

    try {
      // try a delete with a negative version
      version2 = deleteAndGetVersion("1", params("_version_", Long.toString(-version)));
      fail();
    } catch (SolrException se) {
      assertEquals(409, se.code());
    }

    try {
      // try a delete with a greater version
      version2 = deleteAndGetVersion("1", params("_version_", Long.toString(version+random().nextInt(1000)+1)));
      fail();
    } catch (SolrException se) {
      assertEquals(409, se.code());
    }

    try {
      // try a delete of a document that doesn't exist, specifying a specific version
      version2 = deleteAndGetVersion("I_do_not_exist", params("_version_", Long.toString(version)));
      fail();
    } catch (SolrException se) {
      assertEquals(409, se.code());
    }

    // try a delete of a document that doesn't exist, specifying that it should not
    version2 = deleteAndGetVersion("I_do_not_exist", params("_version_", Long.toString(-1)));
    assertTrue(version2 < 0);

    // overwrite the document
    version2 = addAndGetVersion(sdoc("id","1", "_version_", Long.toString(version)), null);
    assertTrue(version2 > version);

    try {
      // overwriting the previous version should now fail
      version2 = addAndGetVersion(sdoc("id","1"), params("_version_", Long.toString(version)));
      fail();
    } catch (SolrException se) {
      assertEquals(409, se.code());
    }

    try {
      // deleting the previous version should now fail
      version2 = deleteAndGetVersion("1", params("_version_", Long.toString(version)));
      fail();
    } catch (SolrException se) {
      assertEquals(409, se.code());
    }

    version = version2;

    // deleting the current version should work
    version2 = deleteAndGetVersion("1", params("_version_", Long.toString(version)));

    try {
      // overwriting the previous existing doc should now fail (since it was deleted)
      version2 = addAndGetVersion(sdoc("id","1"), params("_version_", Long.toString(version)));
      fail();
    } catch (SolrException se) {
      assertEquals(409, se.code());
    }

    try {
      // deleting the previous existing doc should now fail (since it was deleted)
      version2 = deleteAndGetVersion("1", params("_version_", Long.toString(version)));
      fail();
    } catch (SolrException se) {
      assertEquals(409, se.code());
    }

    // overwriting a negative version should work
    version2 = addAndGetVersion(sdoc("id","1"), params("_version_", Long.toString(-(version-1))));
    assertTrue(version2 > version);
    version = version2;

    // sanity test that we see the right version via rtg
    assertJQ(req("qt","/get","id","1")
        ,"=={'doc':{'id':'1','_version_':" + version + "}}"
    );
  }


    /***
    @Test
    public void testGetRealtime() throws Exception {
      SolrQueryRequest sr1 = req("q","foo");
      IndexReader r1 = sr1.getCore().getRealtimeReader();

      assertU(adoc("id","1"));

      IndexReader r2 = sr1.getCore().getRealtimeReader();
      assertNotSame(r1, r2);
      int refcount = r2.getRefCount();

      // make sure a new reader wasn't opened
      IndexReader r3 = sr1.getCore().getRealtimeReader();
      assertSame(r2, r3);
      assertEquals(refcount+1, r3.getRefCount());

      assertU(commit());

      // this is not critical, but currently a commit does not refresh the reader
      // if nothing has changed
      IndexReader r4 = sr1.getCore().getRealtimeReader();
      assertEquals(refcount+2, r4.getRefCount());


      r1.decRef();
      r2.decRef();
      r3.decRef();
      r4.decRef();
      sr1.close();
    }
    ***/


  @Test
  public void testStressGetRealtime() throws Exception {
    clearIndex();
    assertU(commit());

    // req().getCore().getUpdateHandler().getIndexWriterProvider().getIndexWriter(req().getCore()).setInfoStream(System.out);

    final int commitPercent = 5 + random().nextInt(20);
    final int softCommitPercent = 30+random().nextInt(75); // what percent of the commits are soft
    final int deletePercent = 4+random().nextInt(25);
    final int deleteByQueryPercent = 1+random().nextInt(5);
    final int optimisticPercent = 1+random().nextInt(50);    // percent change that an update uses optimistic locking
    final int optimisticCorrectPercent = 25+random().nextInt(70);    // percent change that a version specified will be correct
    final int ndocs = 5 + (random().nextBoolean() ? random().nextInt(25) : random().nextInt(200));
    int nWriteThreads = 5 + random().nextInt(25);

    final int maxConcurrentCommits = nWriteThreads;   // number of committers at a time... it should be <= maxWarmingSearchers

        // query variables
    final int percentRealtimeQuery = 60;
    final AtomicLong operations = new AtomicLong(50000);  // number of query operations to perform in total
    int nReadThreads = 5 + random().nextInt(25);


    verbose("commitPercent=", commitPercent);
    verbose("softCommitPercent=",softCommitPercent);
    verbose("deletePercent=",deletePercent);
    verbose("deleteByQueryPercent=", deleteByQueryPercent);
    verbose("ndocs=", ndocs);
    verbose("nWriteThreads=", nWriteThreads);
    verbose("nReadThreads=", nReadThreads);
    verbose("percentRealtimeQuery=", percentRealtimeQuery);
    verbose("maxConcurrentCommits=", maxConcurrentCommits);
    verbose("operations=", operations);


    initModel(ndocs);

    final AtomicInteger numCommitting = new AtomicInteger();

    List<Thread> threads = new ArrayList<Thread>();

    for (int i=0; i<nWriteThreads; i++) {
      Thread thread = new Thread("WRITER"+i) {
        Random rand = new Random(random().nextInt());

        @Override
        public void run() {
          try {
          while (operations.get() > 0) {
            int oper = rand.nextInt(100);

            if (oper < commitPercent) {
              if (numCommitting.incrementAndGet() <= maxConcurrentCommits) {
                Map<Integer,DocInfo> newCommittedModel;
                long version;

                synchronized(TestRealTimeGet.this) {
                  newCommittedModel = new HashMap<Integer,DocInfo>(model);  // take a snapshot
                  version = snapshotCount++;
                  verbose("took snapshot version=",version);
                }

                if (rand.nextInt(100) < softCommitPercent) {
                  verbose("softCommit start");
                  assertU(TestHarness.commit("softCommit","true"));
                  verbose("softCommit end");
                } else {
                  verbose("hardCommit start");
                  assertU(commit());
                  verbose("hardCommit end");
                }

                synchronized(TestRealTimeGet.this) {
                  // install this model snapshot only if it's newer than the current one
                  if (version >= committedModelClock) {
                    if (VERBOSE) {
                      verbose("installing new committedModel version="+committedModelClock);
                    }
                    committedModel = newCommittedModel;
                    committedModelClock = version;
                  }
                }
              }
              numCommitting.decrementAndGet();
              continue;
            }


            int id = rand.nextInt(ndocs);
            Object sync = syncArr[id];

            // set the lastId before we actually change it sometimes to try and
            // uncover more race conditions between writing and reading
            boolean before = rand.nextBoolean();
            if (before) {
              lastId = id;
            }

            // We can't concurrently update the same document and retain our invariants of increasing values
            // since we can't guarantee what order the updates will be executed.
            // Even with versions, we can't remove the sync because increasing versions does not mean increasing vals.
            synchronized (sync) {
              DocInfo info = model.get(id);

              long val = info.val;
              long nextVal = Math.abs(val)+1;

              if (oper < commitPercent + deletePercent) {
                boolean opt = rand.nextInt() < optimisticPercent;
                boolean correct = opt ? rand.nextInt() < optimisticCorrectPercent : false;
                long badVersion = correct ? 0 : badVersion(rand, info.version);

                if (VERBOSE) {
                  if (!opt) {
                    verbose("deleting id",id,"val=",nextVal);
                  } else {
                    verbose("deleting id",id,"val=",nextVal, "existing_version=",info.version,  (correct ? "" : (" bad_version=" + badVersion)));
                  }
                }

                // assertU("<delete><id>" + id + "</id></delete>");
                Long version = null;

                if (opt) {
                  if (correct) {
                    version = deleteAndGetVersion(Integer.toString(id), params("_version_", Long.toString(info.version)));
                  } else {
                    try {
                      version = deleteAndGetVersion(Integer.toString(id), params("_version_", Long.toString(badVersion)));
                      fail();
                    } catch (SolrException se) {
                      assertEquals(409, se.code());
                    }
                  }
                } else {
                  version = deleteAndGetVersion(Integer.toString(id), null);
                }

                if (version != null) {
                  model.put(id, new DocInfo(version, -nextVal));
                }

                if (VERBOSE) {
                  verbose("deleting id", id, "val=",nextVal,"DONE");
                }
              } else if (oper < commitPercent + deletePercent + deleteByQueryPercent) {
                if (VERBOSE) {
                  verbose("deleteByQuery id ",id, "val=",nextVal);
                }

                assertU("<delete><query>id:" + id + "</query></delete>");
                model.put(id, new DocInfo(-1L, -nextVal));
                if (VERBOSE) {
                  verbose("deleteByQuery id",id, "val=",nextVal,"DONE");
                }
              } else {
                boolean opt = rand.nextInt() < optimisticPercent;
                boolean correct = opt ? rand.nextInt() < optimisticCorrectPercent : false;
                long badVersion = correct ? 0 : badVersion(rand, info.version);

                if (VERBOSE) {
                  if (!opt) {
                    verbose("adding id",id,"val=",nextVal);
                  } else {
                    verbose("adding id",id,"val=",nextVal, "existing_version=",info.version,  (correct ? "" : (" bad_version=" + badVersion)));
                  }
                }

                Long version = null;
                SolrInputDocument sd = sdoc("id", Integer.toString(id), field, Long.toString(nextVal));

                if (opt) {
                  if (correct) {
                    version = addAndGetVersion(sd, params("_version_", Long.toString(info.version)));
                  } else {
                    try {
                      version = addAndGetVersion(sd, params("_version_", Long.toString(badVersion)));
                      fail();
                    } catch (SolrException se) {
                      assertEquals(409, se.code());
                    }
                  }
                } else {
                  version = addAndGetVersion(sd, null);
                }


                if (version != null) {
                  model.put(id, new DocInfo(version, nextVal));
                }

                if (VERBOSE) {
                  verbose("adding id", id, "val=", nextVal,"DONE");
                }

              }
            }   // end sync

            if (!before) {
              lastId = id;
            }
          }
        } catch (Throwable e) {
          operations.set(-1L);
          throw new RuntimeException(e);
        }
        }
      };

      threads.add(thread);
    }


    for (int i=0; i<nReadThreads; i++) {
      Thread thread = new Thread("READER"+i) {
        Random rand = new Random(random().nextInt());

        @Override
        public void run() {
          try {
            while (operations.decrementAndGet() >= 0) {
              // bias toward a recently changed doc
              int id = rand.nextInt(100) < 25 ? lastId : rand.nextInt(ndocs);

              // when indexing, we update the index, then the model
              // so when querying, we should first check the model, and then the index

              boolean realTime = rand.nextInt(100) < percentRealtimeQuery;
              DocInfo info;

              if (realTime) {
                info = model.get(id);
              } else {
                synchronized(TestRealTimeGet.this) {
                  info = committedModel.get(id);
                }
              }

              if (VERBOSE) {
                verbose("querying id", id);
              }
              SolrQueryRequest sreq;
              if (realTime) {
                sreq = req("wt","json", "qt","/get", "ids",Integer.toString(id));
              } else {
                sreq = req("wt","json", "q","id:"+Integer.toString(id), "omitHeader","true");
              }

              String response = h.query(sreq);
              Map rsp = (Map)ObjectBuilder.fromJSON(response);
              List doclist = (List)(((Map)rsp.get("response")).get("docs"));
              if (doclist.size() == 0) {
                // there's no info we can get back with a delete, so not much we can check without further synchronization
              } else {
                assertEquals(1, doclist.size());
                long foundVal = (Long)(((Map)doclist.get(0)).get(field));
                long foundVer = (Long)(((Map)doclist.get(0)).get("_version_"));
                if (foundVal < Math.abs(info.val)
                    || (foundVer == info.version && foundVal != info.val) ) {    // if the version matches, the val must
                  verbose("ERROR, id=", id, "found=",response,"model",info);
                  assertTrue(false);
                }
              }
            }
          }
        catch (Throwable e) {
          operations.set(-1L);
          throw new RuntimeException(e);
        }
      }
      };

      threads.add(thread);
    }


    for (Thread thread : threads) {
      thread.start();
    }

    for (Thread thread : threads) {
      thread.join();
    }

  }


}
