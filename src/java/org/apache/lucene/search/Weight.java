package org.apache.lucene.search;

/**
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

import org.apache.lucene.index.IndexReader;

import java.io.IOException;
import java.io.Serializable;

/**
 * Expert: Calculate query weights and build query scorers.
 * <p>
 * The purpose of {@code Weight} is to ensure searching does not
 * modify a {@code Query}, so that a {@code Query} instance can be reused. <br>
 * {@code Searcher} dependent state of the query should reside in the
 * {@code Weight}. <br>
 * {@code IndexReader} dependent state should reside in the {@code Scorer}.
 * <p>
 * A <code>Weight</code> is used in the following way:
 * <ol>
 * <li>A <code>Weight</code> is constructed by a top-level query, given a
 * <code>Searcher</code> ({@code Query#createWeight(Searcher)}).
 * <li>The {@code #sumOfSquaredWeights()} method is called on the
 * <code>Weight</code> to compute the query normalization factor
 * {@code Similarity#queryNorm(float)} of the query clauses contained in the
 * query.
 * <li>The query normalization factor is passed to {@code #normalize(float)}. At
 * this point the weighting is complete.
 * <li>A <code>Scorer</code> is constructed by {@code #scorer(IndexReader,boolean,boolean)}.
 * </ol>
 * 
 * @since 2.9
 */
public abstract class Weight implements Serializable {

  /**
   * An explanation of the score computation for the named document.
   * 
   * @param reader sub-reader containing the give doc
   * @param doc ...
   * @return an Explanation for the score
   * @throws IOException
   */
  public abstract Explanation explain(IndexReader reader, int doc) throws IOException;

  /** The query that this concerns. */
  public abstract Query getQuery();

  /** The weight for this query. */
  public abstract float getValue();

  /** Assigns the query normalization factor to this. */
  public abstract void normalize(float norm);

  /**
   * Returns a {@code Scorer} which scores documents in/out-of order according
   * to <code>scoreDocsInOrder</code>.
   * <p>
   * <b>NOTE:</b> even if <code>scoreDocsInOrder</code> is false, it is
   * recommended to check whether the returned <code>Scorer</code> indeed scores
   * documents out of order (i.e., call {@code #scoresDocsOutOfOrder()}), as
   * some <code>Scorer</code> implementations will always return documents
   * in-order.<br>
   * <b>NOTE:</b> null can be returned if no documents will be scored by this
   * query.
   * 
   * @param reader
   *          the {@code IndexReader} for which to return the {@code Scorer}.
   * @param scoreDocsInOrder
   *          specifies whether in-order scoring of documents is required. Note
   *          that if set to false (i.e., out-of-order scoring is required),
   *          this method can return whatever scoring mode it supports, as every
   *          in-order scorer is also an out-of-order one. However, an
   *          out-of-order scorer may not support {@code Scorer#nextDoc()}
   *          and/or {@code Scorer#advance(int)}, therefore it is recommended to
   *          request an in-order scorer if use of these methods is required.
   * @param topScorer
   *          if true, {@code Scorer#score(Collector)} will be called; if false,
   *          {@code Scorer#nextDoc()} and/or {@code Scorer#advance(int)} will
   *          be called.
   * @return a {@code Scorer} which scores documents in/out-of order.
   * @throws IOException
   */
  public abstract Scorer scorer(IndexReader reader, boolean scoreDocsInOrder,
      boolean topScorer) throws IOException;
  
  /** The sum of squared weights of contained query clauses. */
  public abstract float sumOfSquaredWeights() throws IOException;

}
