package org.apache.lucene.util.packed;

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

import java.util.Arrays;

/**
 * Direct wrapping of 8 bit values to a backing array of bytes.
 * @lucene.internal
 */

class Direct8 extends PackedInts.ReaderImpl
        implements PackedInts.Mutable {
  private byte[] values;
  private static final int BITS_PER_VALUE = 8;

  public Direct8(int valueCount) {
    super(valueCount, BITS_PER_VALUE);
    values = new byte[valueCount];
  }

  public long get(final int index) {
    assert index >= 0 && index < size();
    return 0xFFL & values[index];
  }

  public void set(final int index, final long value) {
    values[index] = (byte)(value & 0xFF);
  }

  public void clear() {
    Arrays.fill(values, (byte)0);
  }

  @Override
  public Object getArray() {
    return values;
  }

  @Override
  public boolean hasArray() {
    return true;
  }
}
