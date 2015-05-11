package org.apache.lucene.util;

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

import java.lang.management.ManagementFactory;
import java.lang.reflect.*;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.*;

/**
 * Estimates the size (memory representation) of Java objects.
 * 
 * <p>NOTE: Starting with Lucene 3.6, creating instances of this class
 * is deprecated. If you still do this, please note, that instances of
 * {@code RamUsageEstimator} are not thread-safe!
 * It is also deprecated to enable checking of String intern-ness,
 * the new static method no longer allow to do this. Interned strings
 * will be counted as any other object and count for memory usage.
 * 
 * <p>In Lucene 3.6, custom {@code MemoryModel}s were completely
 * removed. The new implementation is now using Hotspot&trade; internals
 * to get the correct scale factors and offsets for calculating
 * memory usage.
 * 
 *
 *
 *
 * 
 * @lucene.internal
 */
public final class RamUsageEstimator {
  /**
   * JVM diagnostic features.
   */
  public enum JvmFeature {
    OBJECT_REFERENCE_SIZE("Object reference size estimated using array index scale"),
    ARRAY_HEADER_SIZE("Array header size estimated using array based offset"),
    FIELD_OFFSETS("Shallow instance size based on field offsets"),
    OBJECT_ALIGNMENT("Object alignment retrieved from HotSpotDiagnostic MX bean");

    public final String description;

    JvmFeature(String description) {
      this.description = description;
    }
    
    @Override
    public String toString() {
      return super.name() + " (" + description + ")";
    }
  }

  /** JVM info string for debugging and reports. */
  public final static String JVM_INFO_STRING;

  /** One kilobyte bytes. */
  public static final long ONE_KB = 1024;
  
  /** One megabyte bytes. */
  public static final long ONE_MB = ONE_KB * ONE_KB;
  
  /** One gigabyte bytes.*/
  public static final long ONE_GB = ONE_KB * ONE_MB;

  public final static int NUM_BYTES_BOOLEAN = 1;
  public final static int NUM_BYTES_BYTE = 1;
  public final static int NUM_BYTES_CHAR = 2;
  public final static int NUM_BYTES_SHORT = 2;
  public final static int NUM_BYTES_INT = 4;
  public final static int NUM_BYTES_FLOAT = 4;
  public final static int NUM_BYTES_LONG = 8;
  public final static int NUM_BYTES_DOUBLE = 8;

  /** 
   * Number of bytes this jvm uses to represent an object reference. 
   */
  public final static int NUM_BYTES_OBJECT_REF;

  /**
   * Number of bytes to represent an object header (no fields, no alignments).
   */
  public final static int NUM_BYTES_OBJECT_HEADER;

  /**
   * Number of bytes to represent an array header (no content, but with alignments).
   */
  public final static int NUM_BYTES_ARRAY_HEADER;
  
  /**
   * A constant specifying the object alignment boundary inside the JVM. Objects will
   * always take a full multiple of this constant, possibly wasting some space. 
   */
  public final static int NUM_BYTES_OBJECT_ALIGNMENT;

  /**
   * Sizes of primitive classes.
   */
  private static final Map<Class<?>,Integer> primitiveSizes;
  static {
    primitiveSizes = new IdentityHashMap<Class<?>,Integer>();
    primitiveSizes.put(boolean.class, NUM_BYTES_BOOLEAN);
    primitiveSizes.put(byte.class, NUM_BYTES_BYTE);
    primitiveSizes.put(char.class, NUM_BYTES_CHAR);
    primitiveSizes.put(short.class, NUM_BYTES_SHORT);
    primitiveSizes.put(int.class, NUM_BYTES_INT);
    primitiveSizes.put(float.class, NUM_BYTES_FLOAT);
    primitiveSizes.put(double.class, NUM_BYTES_DOUBLE);
    primitiveSizes.put(long.class, NUM_BYTES_LONG);
  }

  /**
   * A handle to <code>sun.misc.Unsafe</code>.
   */
  private final static Object theUnsafe;
  
  /**
   * A handle to <code>sun.misc.Unsafe#fieldOffset(Field)</code>.
   */
  private final static Method objectFieldOffsetMethod;

  /**
   * All the supported "internal" JVM features detected at clinit. 
   */
  private final static EnumSet<JvmFeature> supportedFeatures;

  /**
   * Initialize constants and try to collect information about the JVM internals. 
   */
  static {
    // Initialize empirically measured defaults. We'll modify them to the current
    // JVM settings later on if possible.
    int referenceSize = Constants.JRE_IS_64BIT ? 8 : 4;

    supportedFeatures = EnumSet.noneOf(JvmFeature.class);

    Class<?> unsafeClass = null;
    Object tempTheUnsafe = null;
    try {
      unsafeClass = Class.forName("sun.misc.Unsafe");
      final Field unsafeField = unsafeClass.getDeclaredField("theUnsafe");
      unsafeField.setAccessible(true);
      tempTheUnsafe = unsafeField.get(null);
    } catch (Exception e) {
      // Ignore.
    }
    theUnsafe = tempTheUnsafe;

    // get object reference size by getting scale factor of Object[] arrays:
    try {
      final Method arrayIndexScaleM = unsafeClass.getMethod("arrayIndexScale", Class.class);
      referenceSize = ((Number) arrayIndexScaleM.invoke(theUnsafe, Object[].class)).intValue();
      supportedFeatures.add(JvmFeature.OBJECT_REFERENCE_SIZE);
    } catch (Exception e) {
      // ignore.
    }

    // "best guess" based on reference size. We will attempt to modify
    // these to exact values if there is supported infrastructure.
    int objectHeader = Constants.JRE_IS_64BIT ? (8 + referenceSize) : 8;
    // The following is objectHeader + NUM_BYTES_INT, but aligned (object alignment)
    // so on 64 bit JVMs it'll be align(16 + 4, @8) = 24.
    int arrayHeader =  Constants.JRE_IS_64BIT ? (8 + 2 * referenceSize) : 12;

    // get the object header size:
    // - first try out if the field offsets are not scaled (see warning in Unsafe docs)
    // - get the object header size by getting the field offset of the first field of a dummy object
    // If the scaling is byte-wise and unsafe is available, enable dynamic size measurement for
    // estimateRamUsage().
    Method tempObjectFieldOffsetMethod = null;
    try {
      final Method objectFieldOffsetM = unsafeClass.getMethod("objectFieldOffset", Field.class);
      final Field dummy1Field = DummyTwoLongObject.class.getDeclaredField("dummy1");
      final int ofs1 = ((Number) objectFieldOffsetM.invoke(theUnsafe, dummy1Field)).intValue();
      final Field dummy2Field = DummyTwoLongObject.class.getDeclaredField("dummy2");
      final int ofs2 = ((Number) objectFieldOffsetM.invoke(theUnsafe, dummy2Field)).intValue();
      if (Math.abs(ofs2 - ofs1) == NUM_BYTES_LONG) {
        final Field baseField = DummyOneFieldObject.class.getDeclaredField("base");
        objectHeader = ((Number) objectFieldOffsetM.invoke(theUnsafe, baseField)).intValue();
        supportedFeatures.add(JvmFeature.FIELD_OFFSETS);
        tempObjectFieldOffsetMethod = objectFieldOffsetM;
      }
    } catch (Exception e) {
      // Ignore.
    }
    objectFieldOffsetMethod = tempObjectFieldOffsetMethod;

    // Get the array header size by retrieving the array base offset
    // (offset of the first element of an array).
    try {
      final Method arrayBaseOffsetM = unsafeClass.getMethod("arrayBaseOffset", Class.class);
      // we calculate that only for byte[] arrays, it's actually the same for all types:
      arrayHeader = ((Number) arrayBaseOffsetM.invoke(theUnsafe, byte[].class)).intValue();
      supportedFeatures.add(JvmFeature.ARRAY_HEADER_SIZE);
    } catch (Exception e) {
      // Ignore.
    }

    NUM_BYTES_OBJECT_REF = referenceSize;
    NUM_BYTES_OBJECT_HEADER = objectHeader;
    NUM_BYTES_ARRAY_HEADER = arrayHeader;
    
    // Try to get the object alignment (the default seems to be 8 on Hotspot, 
    // regardless of the architecture). Retrieval only works with Java 6.
    int objectAlignment = 8;
    try {
      final Class<?> beanClazz = Class.forName("com.sun.management.HotSpotDiagnosticMXBean");
      final Object hotSpotBean = ManagementFactory.newPlatformMXBeanProxy(
        ManagementFactory.getPlatformMBeanServer(),
        "com.sun.management:type=HotSpotDiagnostic",
        beanClazz
      );
      final Method getVMOptionMethod = beanClazz.getMethod("getVMOption", String.class);
      final Object vmOption = getVMOptionMethod.invoke(hotSpotBean, "ObjectAlignmentInBytes");
      objectAlignment = Integer.parseInt(
          vmOption.getClass().getMethod("getValue").invoke(vmOption).toString()
      );
      supportedFeatures.add(JvmFeature.OBJECT_ALIGNMENT);
    } catch (Exception e) {
      // Ignore.
    }

    NUM_BYTES_OBJECT_ALIGNMENT = objectAlignment;

    JVM_INFO_STRING = "[JVM: " +
        Constants.JVM_NAME + ", " + Constants.JVM_VERSION + ", " + Constants.JVM_VENDOR + ", " + 
        Constants.JAVA_VENDOR + ", " + Constants.JAVA_VERSION + "]";
  }

  /**
   * Cached information about a given class.   
   */
  private static final class ClassCache {
    public final long alignedShallowInstanceSize;
    public final Field[] referenceFields;

    public ClassCache(long alignedShallowInstanceSize, Field[] referenceFields) {
      this.alignedShallowInstanceSize = alignedShallowInstanceSize;
      this.referenceFields = referenceFields;
    }    
  }

  // Object with just one field to determine the object header size by getting the offset of the dummy field:
  @SuppressWarnings("unused")
  private static final class DummyOneFieldObject {
    public byte base;
  }

  // Another test object for checking, if the difference in offsets of dummy1 and dummy2 is 8 bytes.
  // Only then we can be sure that those are real, unscaled offsets:
  @SuppressWarnings("unused")
  private static final class DummyTwoLongObject {
    public long dummy1, dummy2;
  }

  /**
   * Aligns an object size to be the next multiple of {@code #NUM_BYTES_OBJECT_ALIGNMENT}.
   */
  public static long alignObjectSize(long size) {
    size += (long) NUM_BYTES_OBJECT_ALIGNMENT - 1L;
    return size - (size % NUM_BYTES_OBJECT_ALIGNMENT);
  }
  
  /** Returns the size in bytes of the byte[] object. */
  public static long sizeOf(byte[] arr) {
    return alignObjectSize((long) NUM_BYTES_ARRAY_HEADER + arr.length);
  }

  /** Returns the size in bytes of the short[] object. */
  public static long sizeOf(short[] arr) {
    return alignObjectSize((long) NUM_BYTES_ARRAY_HEADER + (long) NUM_BYTES_SHORT * arr.length);
  }
  
  /** Returns the size in bytes of the int[] object. */
  public static long sizeOf(int[] arr) {
    return alignObjectSize((long) NUM_BYTES_ARRAY_HEADER + (long) NUM_BYTES_INT * arr.length);
  }

  /** Returns the size in bytes of the long[] object. */
  public static long sizeOf(long[] arr) {
    return alignObjectSize((long) NUM_BYTES_ARRAY_HEADER + (long) NUM_BYTES_LONG * arr.length);
  }

  /**
   * Estimates the RAM usage by the given object. It will
   * walk the object tree and sum up all referenced objects.
   * 
   * <p><b>Resource Usage:</b> This method internally uses a set of
   * every object seen during traversals so it does allocate memory
   * (it isn't side-effect free). After the method exits, this memory
   * should be GCed.</p>
   */
  public static long sizeOf(Object obj) {
    return measureObjectSize(obj, false);
  }

  /*
   * Non-recursive version of object descend. This consumes more memory than recursive in-depth 
   * traversal but prevents stack overflows on long chains of objects
   * or complex graphs (a max. recursion depth on my machine was ~5000 objects linked in a chain
   * so not too much).  
   */
  private static long measureObjectSize(Object root, boolean checkInterned) {
    // Objects seen so far.
    final IdentityHashSet<Object> seen = new IdentityHashSet<Object>();
    // Class cache with reference Field and precalculated shallow size. 
    final IdentityHashMap<Class<?>, ClassCache> classCache = new IdentityHashMap<Class<?>, ClassCache>();
    // Stack of objects pending traversal. Recursion caused stack overflows. 
    final ArrayList<Object> stack = new ArrayList<Object>();
    stack.add(root);

    long totalSize = 0;
    while (!stack.isEmpty()) {
      final Object ob = stack.remove(stack.size() - 1);

      if (ob == null || seen.contains(ob)) {
        continue;
      }
      seen.add(ob);

      // *** BEGIN deprecation
      if (checkInterned && ob instanceof String && ob == ((String) ob).intern()) {
        continue;
      }
      // *** END deprecation
      
      final Class<?> obClazz = ob.getClass();
      if (obClazz.isArray()) {
        /*
         * Consider an array, possibly of primitive types. Push any of its references to
         * the processing stack and accumulate this array's shallow size. 
         */
        long size = NUM_BYTES_ARRAY_HEADER;
        final int len = Array.getLength(ob);
        if (len > 0) {
          Class<?> componentClazz = obClazz.getComponentType();
          if (componentClazz.isPrimitive()) {
            size += (long) len * primitiveSizes.get(componentClazz);
          } else {
            size += (long) NUM_BYTES_OBJECT_REF * len;

            // Push refs for traversal later.
            for (int i = len; --i >= 0 ;) {
              final Object o = Array.get(ob, i);
              if (o != null && !seen.contains(o)) {
                stack.add(o);
              }
            }            
          }
        }
        totalSize += alignObjectSize(size);
      } else {
        /*
         * Consider an object. Push any references it has to the processing stack
         * and accumulate this object's shallow size. 
         */
        try {
          ClassCache cachedInfo = classCache.get(obClazz);
          if (cachedInfo == null) {
            classCache.put(obClazz, cachedInfo = createCacheEntry(obClazz));
          }

          for (Field f : cachedInfo.referenceFields) {
            // Fast path to eliminate redundancies.
            final Object o = f.get(ob);
            if (o != null && !seen.contains(o)) {
              stack.add(o);
            }
          }

          totalSize += cachedInfo.alignedShallowInstanceSize;
        } catch (IllegalAccessException e) {
          // this should never happen as we enabled setAccessible().
          throw new RuntimeException("Reflective field access failed?", e);
        }
      }
    }

    // Help the GC (?).
    seen.clear();
    stack.clear();
    classCache.clear();

    return totalSize;
  }

  /**
   * Create a cached information about shallow size and reference fields for 
   * a given class.
   */
  private static ClassCache createCacheEntry(final Class<?> clazz) {
    ClassCache cachedInfo;
    long shallowInstanceSize = NUM_BYTES_OBJECT_HEADER;
    final ArrayList<Field> referenceFields = new ArrayList<Field>(32);
    for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
      final Field[] fields = c.getDeclaredFields();
      for (final Field f : fields) {
        if (!Modifier.isStatic(f.getModifiers())) {
          shallowInstanceSize = adjustForField(shallowInstanceSize, f);

          if (!f.getType().isPrimitive()) {
            f.setAccessible(true);
            referenceFields.add(f);
          }
        }
      }
    }

    cachedInfo = new ClassCache(
        alignObjectSize(shallowInstanceSize), 
        referenceFields.toArray(new Field[referenceFields.size()]));
    return cachedInfo;
  }

  /**
   * This method returns the maximum representation size of an object. <code>sizeSoFar</code>
   * is the object's size measured so far. <code>f</code> is the field being probed.
   * 
   * <p>The returned offset will be the maximum of whatever was measured so far and 
   * <code>f</code> field's offset and representation size (unaligned).
   */
  private static long adjustForField(long sizeSoFar, final Field f) {
    final Class<?> type = f.getType();
    final int fsize = type.isPrimitive() ? primitiveSizes.get(type) : NUM_BYTES_OBJECT_REF;
    if (objectFieldOffsetMethod != null) {
      try {
        final long offsetPlusSize =
          ((Number) objectFieldOffsetMethod.invoke(theUnsafe, f)).longValue() + fsize;
        return Math.max(sizeSoFar, offsetPlusSize);
      } catch (IllegalAccessException ex) {
        throw new RuntimeException("Access problem with sun.misc.Unsafe", ex);
      } catch (InvocationTargetException ite) {
        final Throwable cause = ite.getCause();
        if (cause instanceof RuntimeException)
          throw (RuntimeException) cause;
        if (cause instanceof Error)
          throw (Error) cause;
        // this should never happen (Unsafe does not declare
        // checked Exceptions for this method), but who knows?
        throw new RuntimeException("Call to Unsafe's objectFieldOffset() throwed "+
          "checked Exception when accessing field " +
          f.getDeclaringClass().getName() + "#" + f.getName(), cause);
      }
    } else {
      // TODO: No alignments based on field type/ subclass fields alignments?
      return sizeSoFar + fsize;
    }
  }

  /**
   * Returns <code>size</code> in human-readable units (GB, MB, KB or bytes).
   */
  public static String humanReadableUnits(long bytes) {
    return humanReadableUnits(bytes, 
        new DecimalFormat("0.#", new DecimalFormatSymbols(Locale.ENGLISH)));
  }

  /**
   * Returns <code>size</code> in human-readable units (GB, MB, KB or bytes). 
   */
  public static String humanReadableUnits(long bytes, DecimalFormat df) {
    if (bytes / ONE_GB > 0) {
      return df.format((float) bytes / ONE_GB) + " GB";
    } else if (bytes / ONE_MB > 0) {
      return df.format((float) bytes / ONE_MB) + " MB";
    } else if (bytes / ONE_KB > 0) {
      return df.format((float) bytes / ONE_KB) + " KB";
    } else {
      return bytes + " bytes";
    }
  }

  /**
   * An identity hash set implemented using open addressing. No null keys are allowed.
   * 
   * TODO: If this is useful outside this class, make it public - needs some work
   */
  static final class IdentityHashSet<KType> implements Iterable<KType> {
    /**
     * Default load factor.
     */
    public final static float DEFAULT_LOAD_FACTOR = 0.75f;

    /**
     * Minimum capacity for the set.
     */
    public final static int MIN_CAPACITY = 4;

    /**
     * All of set entries. Always of power of two length.
     */
    public Object[] keys;
    
    /**
     * Cached number of assigned slots.
     */
    public int assigned;
    
    /**
     * The load factor for this set (fraction of allocated or deleted slots before
     * the buffers must be rehashed or reallocated).
     */
    public final float loadFactor;
    
    /**
     * Cached capacity threshold at which we must resize the buffers.
     */
    private int resizeThreshold;
    
    /**
     * Creates a hash set with the default capacity of 16.
     * load factor of {@value #DEFAULT_LOAD_FACTOR}. `
     */
    public IdentityHashSet() {
      this(16, DEFAULT_LOAD_FACTOR);
    }

    /**
     * Creates a hash set with the given capacity and load factor.
     */
    public IdentityHashSet(int initialCapacity, float loadFactor) {
      initialCapacity = Math.max(MIN_CAPACITY, initialCapacity);
      
      assert initialCapacity > 0 : "Initial capacity must be between (0, "
          + Integer.MAX_VALUE + "].";
      assert loadFactor > 0 && loadFactor < 1 : "Load factor must be between (0, 1).";
      this.loadFactor = loadFactor;
      allocateBuffers(roundCapacity(initialCapacity));
    }
    
    /**
     * Adds a reference to the set. Null keys are not allowed.
     */
    public boolean add(KType e) {
      assert e != null : "Null keys not allowed.";
      
      if (assigned >= resizeThreshold) expandAndRehash();
      
      final int mask = keys.length - 1;
      int slot = rehash(e) & mask;
      Object existing;
      while ((existing = keys[slot]) != null) {
        if (e == existing) {
          return false; // already found.
        }
        slot = (slot + 1) & mask;
      }
      assigned++;
      keys[slot] = e;
      return true;
    }

    /**
     * Checks if the set contains a given ref.
     */
    public boolean contains(KType e) {
      final int mask = keys.length - 1;
      int slot = rehash(e) & mask;
      Object existing;
      while ((existing = keys[slot]) != null) {
        if (e == existing) {
          return true;
        }
        slot = (slot + 1) & mask;
      }
      return false;
    }

    /** Rehash via MurmurHash.
     * 
     * <p>The implementation is based on the
     * finalization step from Austin Appleby's
     * <code>MurmurHash3</code>.
     * 
     *
     */
    private static int rehash(Object o) {
      int k = System.identityHashCode(o);
      k ^= k >>> 16;
      k *= 0x85ebca6b;
      k ^= k >>> 13;
      k *= 0xc2b2ae35;
      k ^= k >>> 16;
      return k;
    }
    
    /**
     * Expand the internal storage buffers (capacity) or rehash current keys and
     * values if there are a lot of deleted slots.
     */
    private void expandAndRehash() {
      final Object[] oldKeys = this.keys;
      
      assert assigned >= resizeThreshold;
      allocateBuffers(nextCapacity(keys.length));
      
      /*
       * Rehash all assigned slots from the old hash table.
       */
      final int mask = keys.length - 1;
      for (final Object key : oldKeys) {
        if (key != null) {
          int slot = rehash(key) & mask;
          while (keys[slot] != null) {
            slot = (slot + 1) & mask;
          }
          keys[slot] = key;
        }
      }
      Arrays.fill(oldKeys, null);
    }
    
    /**
     * Allocate internal buffers for a given capacity.
     * 
     * @param capacity
     *          New capacity (must be a power of two).
     */
    private void allocateBuffers(int capacity) {
      this.keys = new Object[capacity];
      this.resizeThreshold = (int) (capacity * DEFAULT_LOAD_FACTOR);
    }
    
    /**
     * Return the next possible capacity, counting from the current buffers' size.
     */
    protected int nextCapacity(int current) {
      assert current > 0 && Long.bitCount(current) == 1 : "Capacity must be a power of two.";
      assert ((current << 1) > 0) : "Maximum capacity exceeded ("
          + (0x80000000 >>> 1) + ").";
      
      if (current < MIN_CAPACITY / 2) current = MIN_CAPACITY / 2;
      return current << 1;
    }
    
    /**
     * Round the capacity to the next allowed value.
     */
    protected int roundCapacity(int requestedCapacity) {
      // Maximum positive integer that is a power of two.
      if (requestedCapacity > (0x80000000 >>> 1)) return (0x80000000 >>> 1);
      
      int capacity = MIN_CAPACITY;
      while (capacity < requestedCapacity) {
        capacity <<= 1;
      }

      return capacity;
    }
    
    public void clear() {
      assigned = 0;
      Arrays.fill(keys, null);
    }
    
    public int size() {
      return assigned;
    }
    
    public boolean isEmpty() {
      return size() == 0;
    }

    //@Override
    public Iterator<KType> iterator() {
      return new Iterator<KType>() {
        int pos = -1;
        Object nextElement = fetchNext();

        //@Override
        public boolean hasNext() {
          return nextElement != null;
        }

        @SuppressWarnings("unchecked")
        //@Override
        public KType next() {
          Object r = this.nextElement;
          if (r == null) {
            throw new NoSuchElementException();
          }
          this.nextElement = fetchNext();
          return (KType) r;
        }

        private Object fetchNext() {
          pos++;
          while (pos < keys.length && keys[pos] == null) {
            pos++;
          }

          return (pos >= keys.length ? null : keys[pos]);
        }

        //@Override
        public void remove() {
          throw new UnsupportedOperationException();
        }
      };
    }
  }

  /** Creates a new instance of {@code RamUsageEstimator} with intern checking
   * enabled. Don't ever use this method, as intern checking is deprecated,
   * because it is not free of side-effects and strains the garbage collector
   * additionally.
   * @deprecated Don't create instances of this class, instead use the static
   * {@code #sizeOf(Object)} method that has no intern checking, too.
   */
  @Deprecated
  public RamUsageEstimator() {
  }

}
