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

package org.apache.lucene.codecs.lucene99;

import java.io.IOException;
import java.nio.ByteBuffer;
import org.apache.lucene.codecs.lucene90.IndexedDISI;
import org.apache.lucene.codecs.lucene95.OrdToDocDISIReaderConfiguration;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.packed.DirectMonotonicReader;
import org.apache.lucene.util.quantization.QuantizedByteVectorValues;
import org.apache.lucene.util.quantization.RandomAccessQuantizedByteVectorValues;
import org.apache.lucene.util.quantization.ScalarQuantizer;

/**
 * Read the quantized vector values and their score correction values from the index input. This
 * supports both iterated and random access.
 */
public abstract class OffHeapQuantizedByteVectorValues extends QuantizedByteVectorValues
    implements RandomAccessQuantizedByteVectorValues {

  protected final int dimension;
  protected final int size;
  protected final int numBytes;
  protected final ScalarQuantizer scalarQuantizer;
  protected final boolean compress;

  protected final IndexInput slice;
  protected final byte[] binaryValue;
  protected final ByteBuffer byteBuffer;
  protected final int byteSize;
  protected int lastOrd = -1;
  protected final float[] scoreCorrectionConstant = new float[1];

  static void decompressBytes(byte[] compressed, int numBytes) {
    if (numBytes == compressed.length) {
      return;
    }
    if (numBytes << 1 != compressed.length) {
      throw new IllegalArgumentException(
          "numBytes: " + numBytes + " does not match compressed length: " + compressed.length);
    }
    for (int i = 0; i < numBytes; ++i) {
      compressed[numBytes + i] = (byte) (compressed[i] & 0x0F);
      compressed[i] = (byte) ((compressed[i] & 0xFF) >> 4);
    }
  }

  static byte[] compressedArray(int dimension, byte bits) {
    if (bits <= 4) {
      return new byte[(dimension + 1) >> 1];
    } else {
      return null;
    }
  }

  static void compressBytes(byte[] raw, byte[] compressed) {
    if (compressed.length != ((raw.length + 1) >> 1)) {
      throw new IllegalArgumentException(
          "compressed length: " + compressed.length + " does not match raw length: " + raw.length);
    }
    for (int i = 0; i < compressed.length; ++i) {
      int v = (raw[i] << 4) | raw[compressed.length + i];
      compressed[i] = (byte) v;
    }
  }

  OffHeapQuantizedByteVectorValues(
      int dimension,
      int size,
      ScalarQuantizer scalarQuantizer,
      boolean compress,
      IndexInput slice) {
    this.dimension = dimension;
    this.size = size;
    this.slice = slice;
    this.scalarQuantizer = scalarQuantizer;
    this.compress = compress;
    if (scalarQuantizer.getBits() <= 4 && compress) {
      this.numBytes = (dimension + 1) >> 1;
    } else {
      this.numBytes = dimension;
    }
    this.byteSize = this.numBytes + Float.BYTES;
    byteBuffer = ByteBuffer.allocate(dimension);
    binaryValue = byteBuffer.array();
  }

  @Override
  public ScalarQuantizer getScalarQuantizer() {
    return scalarQuantizer;
  }

  @Override
  public int dimension() {
    return dimension;
  }

  @Override
  public int size() {
    return size;
  }

  @Override
  public byte[] vectorValue(int targetOrd) throws IOException {
    if (lastOrd == targetOrd) {
      return binaryValue;
    }
    slice.seek((long) targetOrd * byteSize);
    slice.readBytes(byteBuffer.array(), byteBuffer.arrayOffset(), numBytes);
    slice.readFloats(scoreCorrectionConstant, 0, 1);
    decompressBytes(binaryValue, numBytes);
    lastOrd = targetOrd;
    return binaryValue;
  }

  @Override
  public float getScoreCorrectionConstant() {
    return scoreCorrectionConstant[0];
  }

  public static OffHeapQuantizedByteVectorValues load(
      OrdToDocDISIReaderConfiguration configuration,
      int dimension,
      int size,
      ScalarQuantizer scalarQuantizer,
      boolean compress,
      long quantizedVectorDataOffset,
      long quantizedVectorDataLength,
      IndexInput vectorData)
      throws IOException {
    if (configuration.isEmpty()) {
      return new EmptyOffHeapVectorValues(dimension);
    }
    IndexInput bytesSlice =
        vectorData.slice(
            "quantized-vector-data", quantizedVectorDataOffset, quantizedVectorDataLength);
    if (configuration.isDense()) {
      return new DenseOffHeapVectorValues(dimension, size, scalarQuantizer, compress, bytesSlice);
    } else {
      return new SparseOffHeapVectorValues(
          configuration, dimension, size, scalarQuantizer, compress, vectorData, bytesSlice);
    }
  }

  /**
   * Dense vector values that are stored off-heap. This is the most common case when every doc has a
   * vector.
   */
  public static class DenseOffHeapVectorValues extends OffHeapQuantizedByteVectorValues {

    private int doc = -1;

    public DenseOffHeapVectorValues(
        int dimension,
        int size,
        ScalarQuantizer scalarQuantizer,
        boolean compress,
        IndexInput slice) {
      super(dimension, size, scalarQuantizer, compress, slice);
    }

    @Override
    public byte[] vectorValue() throws IOException {
      return vectorValue(doc);
    }

    @Override
    public int docID() {
      return doc;
    }

    @Override
    public int nextDoc() throws IOException {
      return advance(doc + 1);
    }

    @Override
    public int advance(int target) throws IOException {
      assert docID() < target;
      if (target >= size) {
        return doc = NO_MORE_DOCS;
      }
      return doc = target;
    }

    @Override
    public DenseOffHeapVectorValues copy() throws IOException {
      return new DenseOffHeapVectorValues(
          dimension, size, scalarQuantizer, compress, slice.clone());
    }

    @Override
    public Bits getAcceptOrds(Bits acceptDocs) {
      return acceptDocs;
    }
  }

  private static class SparseOffHeapVectorValues extends OffHeapQuantizedByteVectorValues {
    private final DirectMonotonicReader ordToDoc;
    private final IndexedDISI disi;
    // dataIn was used to init a new IndexedDIS for #randomAccess()
    private final IndexInput dataIn;
    private final OrdToDocDISIReaderConfiguration configuration;

    public SparseOffHeapVectorValues(
        OrdToDocDISIReaderConfiguration configuration,
        int dimension,
        int size,
        ScalarQuantizer scalarQuantizer,
        boolean compress,
        IndexInput dataIn,
        IndexInput slice)
        throws IOException {
      super(dimension, size, scalarQuantizer, compress, slice);
      this.configuration = configuration;
      this.dataIn = dataIn;
      this.ordToDoc = configuration.getDirectMonotonicReader(dataIn);
      this.disi = configuration.getIndexedDISI(dataIn);
    }

    @Override
    public byte[] vectorValue() throws IOException {
      return vectorValue(disi.index());
    }

    @Override
    public int docID() {
      return disi.docID();
    }

    @Override
    public int nextDoc() throws IOException {
      return disi.nextDoc();
    }

    @Override
    public int advance(int target) throws IOException {
      assert docID() < target;
      return disi.advance(target);
    }

    @Override
    public SparseOffHeapVectorValues copy() throws IOException {
      return new SparseOffHeapVectorValues(
          configuration, dimension, size, scalarQuantizer, compress, dataIn, slice.clone());
    }

    @Override
    public int ordToDoc(int ord) {
      return (int) ordToDoc.get(ord);
    }

    @Override
    public Bits getAcceptOrds(Bits acceptDocs) {
      if (acceptDocs == null) {
        return null;
      }
      return new Bits() {
        @Override
        public boolean get(int index) {
          return acceptDocs.get(ordToDoc(index));
        }

        @Override
        public int length() {
          return size;
        }
      };
    }
  }

  private static class EmptyOffHeapVectorValues extends OffHeapQuantizedByteVectorValues {

    public EmptyOffHeapVectorValues(int dimension) {
      super(dimension, 0, new ScalarQuantizer(-1, 1, (byte) 7), false, null);
    }

    private int doc = -1;

    @Override
    public int dimension() {
      return super.dimension();
    }

    @Override
    public int size() {
      return 0;
    }

    @Override
    public byte[] vectorValue() {
      throw new UnsupportedOperationException();
    }

    @Override
    public int docID() {
      return doc;
    }

    @Override
    public int nextDoc() throws IOException {
      return advance(doc + 1);
    }

    @Override
    public int advance(int target) {
      return doc = NO_MORE_DOCS;
    }

    @Override
    public EmptyOffHeapVectorValues copy() {
      throw new UnsupportedOperationException();
    }

    @Override
    public byte[] vectorValue(int targetOrd) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int ordToDoc(int ord) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Bits getAcceptOrds(Bits acceptDocs) {
      return null;
    }
  }
}
