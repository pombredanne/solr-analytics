package org.apache.lucene.spatial.query;

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

import com.spatial4j.core.context.SpatialContext;
import com.spatial4j.core.context.simple.SimpleSpatialContext;
import com.spatial4j.core.shape.Rectangle;
import org.apache.lucene.util.LuceneTestCase;
import org.junit.Test;


public class SpatialArgsParserTest extends LuceneTestCase {

  private SpatialContext ctx = SimpleSpatialContext.GEO_KM;

  //The args parser is only dependent on the ctx for IO so I don't care to test
  // with other implementations.

  @Test
  public void testArgsParser() throws Exception {
    SpatialArgsParser parser = new SpatialArgsParser();

    String arg = SpatialOperation.IsWithin + "(-10 -20 10 20)";
    SpatialArgs out = parser.parse(arg, ctx);
    assertEquals(SpatialOperation.IsWithin, out.getOperation());
    Rectangle bounds = (Rectangle) out.getShape();
    assertEquals(-10.0, bounds.getMinX(), 0D);
    assertEquals(10.0, bounds.getMaxX(), 0D);

    // Disjoint should not be scored
    arg = SpatialOperation.IsDisjointTo + " (-10 10 -20 20)";
    out = parser.parse(arg, ctx);
    assertEquals(SpatialOperation.IsDisjointTo, out.getOperation());

    try {
      parser.parse(SpatialOperation.IsDisjointTo + "[ ]", ctx);
      fail("spatial operations need args");
    }
    catch (Exception ex) {//expected
    }

    try {
      parser.parse("XXXX(-10 10 -20 20)", ctx);
      fail("unknown operation!");
    }
    catch (Exception ex) {//expected
    }
  }

}
