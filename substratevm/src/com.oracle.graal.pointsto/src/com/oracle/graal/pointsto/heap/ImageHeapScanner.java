/*
 * Copyright (c) 2021, 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.graal.pointsto.heap;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.graalvm.collections.EconomicMap;
import org.graalvm.collections.MapCursor;
import org.graalvm.compiler.api.replacements.SnippetReflectionProvider;
import org.graalvm.compiler.debug.GraalError;

import com.oracle.graal.pointsto.ObjectScanningObserver;
import com.oracle.graal.pointsto.PointsToAnalysis;
import com.oracle.graal.pointsto.api.HostVM;
import com.oracle.graal.pointsto.heap.ImageHeap.ImageHeapArray;
import com.oracle.graal.pointsto.heap.ImageHeap.ImageHeapInstance;
import com.oracle.graal.pointsto.heap.ImageHeap.ImageHeapObject;
import com.oracle.graal.pointsto.heap.value.ValueSupplier;
import com.oracle.graal.pointsto.meta.AnalysisField;
import com.oracle.graal.pointsto.meta.AnalysisMetaAccess;
import com.oracle.graal.pointsto.meta.AnalysisMethod;
import com.oracle.graal.pointsto.meta.AnalysisType;
import com.oracle.graal.pointsto.meta.AnalysisUniverse;
import com.oracle.graal.pointsto.meta.TypeData;
import com.oracle.graal.pointsto.util.AnalysisError;
import com.oracle.graal.pointsto.util.AnalysisFuture;
import com.oracle.graal.pointsto.util.GraalAccess;

import jdk.vm.ci.code.BytecodePosition;
import jdk.vm.ci.meta.Constant;
import jdk.vm.ci.meta.ConstantReflectionProvider;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.ResolvedJavaField;

/**
 * Scanning is triggered when:
 * <ul>
 * <li>a static final field is marked as accessed, or</li>s
 * <li>a method that is parsed and embedded roots are discovered</li>
 * </ul>
 * <p>
 * When an instance field is marked as accessed the objects of its declaring type (and all the
 * subtypes) are re-scanned.
 */
public class ImageHeapScanner {

    protected final ImageHeap imageHeap;
    protected final AnalysisMetaAccess metaAccess;
    protected final AnalysisUniverse universe;
    protected final HostVM hostVM;

    protected final SnippetReflectionProvider snippetReflection;
    protected final ConstantReflectionProvider constantReflection;
    protected final ConstantReflectionProvider hostedConstantReflection;
    protected final SnippetReflectionProvider hostedSnippetReflection;
    protected ObjectScanningObserver scanningObserver;

    public ImageHeapScanner(ImageHeap heap, AnalysisUniverse aUniverse, AnalysisMetaAccess aMetaAccess,
                    SnippetReflectionProvider aSnippetReflection, ConstantReflectionProvider aConstantReflection, ObjectScanningObserver aScanningObserver) {
        imageHeap = heap;
        universe = aUniverse;
        metaAccess = aMetaAccess;
        hostVM = aUniverse.hostVM();
        snippetReflection = aSnippetReflection;
        constantReflection = aConstantReflection;
        scanningObserver = aScanningObserver;
        hostedConstantReflection = GraalAccess.getOriginalProviders().getConstantReflection();
        hostedSnippetReflection = GraalAccess.getOriginalProviders().getSnippetReflection();
    }

    public ImageHeap getImageHeap() {
        return imageHeap;
    }

    public JavaConstant scanFieldValue(AnalysisField field, JavaConstant receiver) {
        /*
         * If this read is for constant folding then the read is not parsed yet and the field is not
         * marked as reachable.
         */
        field.markReachable();
        JavaConstant value = null;
        if (field.isStatic()) {
            TypeData declaringClassData = field.getDeclaringClass().getOrComputeData();
            value = getSourceConstant(declaringClassData.readStaticFieldValue(field));
        } else {
            ImageHeapObject receiverObject = getImageHeapObject(receiver);
            if (receiverObject instanceof ImageHeapInstance) {
                value = getSourceConstant(((ImageHeapInstance) receiverObject).readFieldValue(field));
            }
        }
        return value;
    }

    private JavaConstant getSourceConstant(JavaConstant constant) {
        /*
         * Access the replaced value via the image heap. The image heap object stores a reference to
         * the source constant, i.e., either the original object, or the replaced object for
         * constants affected by object replacers. This ensures that only replaced objects are
         * embedded in the graphs. Having not-yet-replaced object in a constant in the graphs is
         * dangerous when the object replacer can change the type. Method de-virtualization for
         * example could lead to a wrong direct call target.
         */
        ImageHeapObject heapObject = getImageHeapObject(constant);
        if (heapObject != null) {
            return heapObject.getObject();
        }
        return constant;
    }

    ImageHeapObject getImageHeapObject(Constant constant) {
        if (constant instanceof JavaConstant) {
            JavaConstant javaConstant = (JavaConstant) constant;
            if (javaConstant.getJavaKind() == JavaKind.Object && javaConstant.isNonNull()) {
                return toImageHeapObject(javaConstant);
            }
        }
        return null;
    }

    /**
     * Computes the class initialization status and the snapshot of all static fields. This is an
     * expensive operation and therefore done in an asynchronous task.
     */
    public TypeData computeTypeData(AnalysisType type) {
        GraalError.guarantee(type.isReachable(), "TypeData is only available for reachable types");

        /* Decide if the type should be initialized at build time or at run time: */
        boolean initializeAtRunTime = initializeAtRunTime(type);

        Map<ResolvedJavaField, AnalysisFuture<JavaConstant>> rawStaticFieldValues = null;
        /*
         * Snapshot all static fields. This reads the raw field value of all fields regardless of
         * reachability status. The field value is processed when a field is marked as reachable, in
         * onFieldReachable().
         */
        rawStaticFieldValues = new HashMap<>();
        for (AnalysisField field : type.getStaticFields()) {
            ValueSupplier<JavaConstant> rawFieldValue = readHostedFieldValue(field, null);
            rawStaticFieldValues.put(field, new AnalysisFuture<>(() -> onFieldValueReachable(field, null, rawFieldValue, field)));
        }

        return new TypeData(initializeAtRunTime, rawStaticFieldValues);
    }

    protected boolean initializeAtRunTime(@SuppressWarnings("unused") AnalysisType type) {
        return false;
    }

    void markTypeInstantiated(AnalysisType type, @SuppressWarnings("unused") Reason reason) {
        if (universe.sealed() && !type.isReachable()) {
            throw AnalysisError.shouldNotReachHere("Universe is sealed. New type reachable: " + type.toJavaName());
        }
        type.registerAsInHeap();
    }

    public void onFieldRead(AnalysisField field) {
        assert field.isRead();
        execute(() -> onFieldReadTask(field));
    }

    private void onFieldReadTask(AnalysisField field) {
        AnalysisType declaringClass = field.getDeclaringClass();
        if (field.isStatic()) {
            TypeData declaringClassData = declaringClass.getOrComputeData();
            /* Check if the value is available before accessing it. */
            if (!declaringClassData.initializeAtRunTime() && isValueAvailable(field)) {
                execute(declaringClassData.getStaticFieldValueTask(field));
            }
        } else {
            if (isValueAvailable(field)) {
                onInstanceFieldRead(field, declaringClass);
            }
        }
    }

    private void onInstanceFieldRead(AnalysisField field, AnalysisType type) {
        for (AnalysisType subtype : type.getSubTypes()) {
            for (ImageHeapObject imageHeapObject : imageHeap.getObjects(subtype)) {
                execute(((ImageHeapInstance) imageHeapObject).getFieldTask(field));
            }
            /* Subtypes may include this type itself. */
            if (!subtype.equals(type)) {
                onInstanceFieldRead(field, subtype);
            }
        }
    }

    AnalysisFuture<ImageHeapObject> markConstantReachable(Constant constant, Reason reason) {
        if (!(constant instanceof JavaConstant)) {
            /*
             * The bytecode parser sometimes embeds low-level VM constants for types into the
             * high-level graph. Since these constants are the result of type lookups, these types
             * are already marked as reachable. Eventually, the bytecode parser should be changed to
             * only use JavaConstant.
             */
            return null;
        }

        JavaConstant javaConstant = (JavaConstant) constant;
        if (javaConstant.getJavaKind() == JavaKind.Object && javaConstant.isNonNull()) {
            if (!hostVM.platformSupported(universe, asObject(javaConstant).getClass())) {
                return null;
            }
            return getOrCreateConstantReachableTask(javaConstant, reason);
        }

        return null;
    }

    public ImageHeapObject toImageHeapObject(JavaConstant constant) {
        return toImageHeapObject(constant, Reason.SCAN);
    }

    public ImageHeapObject toImageHeapObject(JavaConstant javaConstant, Reason reason) {
        assert javaConstant.getJavaKind() == JavaKind.Object && javaConstant.isNonNull();
        return getOrCreateConstantReachableTask(javaConstant, reason).ensureDone();
    }

    protected AnalysisFuture<ImageHeapObject> getOrCreateConstantReachableTask(JavaConstant javaConstant, Reason reason) {
        Reason nonNullReason = Objects.requireNonNull(reason);
        AnalysisFuture<ImageHeapObject> existingTask = imageHeap.heapObjects.get(javaConstant);
        if (existingTask == null) {
            /*
             * TODO - this still true? is it ok to seal the heap?
             *
             * The constants can be generated at any stage, not only before/during analysis. For
             * example `com.oracle.svm.core.graal.snippets.SubstrateAllocationSnippets.Templates.
             * getProfilingData()` generates `AllocationProfilingData` during lowering.
             */
            if (universe.sealed()) {
                throw AnalysisError.shouldNotReachHere("Universe is sealed. New constant reachable: " + javaConstant.toValueString());
            }
            AnalysisFuture<ImageHeapObject> newTask = new AnalysisFuture<>(() -> createImageHeapObject(javaConstant, nonNullReason));
            existingTask = imageHeap.heapObjects.putIfAbsent(javaConstant, newTask);
            if (existingTask == null) {
                /*
                 * Immediately schedule the new task. There is no need to have not-yet-reachable
                 * ImageHeapObject.
                 */
                execute(newTask);
                return newTask;
            }
        }
        return existingTask;
    }

    private Optional<JavaConstant> maybeReplace(JavaConstant constant, Reason reason) {
        Object unwrapped = unwrapObject(constant);
        if (unwrapped == null) {
            throw GraalError.shouldNotReachHere(formatReason("Could not unwrap constant", reason));
        } else if (unwrapped instanceof ImageHeapObject) {
            throw GraalError.shouldNotReachHere(formatReason("Double wrapping of constant. Most likely, the reachability analysis code itself is seen as reachable.", reason));
        }

        /* Run all registered object replacers. */
        if (constant.getJavaKind() == JavaKind.Object) {
            Object replaced = universe.replaceObject(unwrapped);
            if (replaced != unwrapped) {
                JavaConstant replacedConstant = universe.getSnippetReflection().forObject(replaced);
                return Optional.of(replacedConstant);
            }
        }
        return Optional.empty();
    }

    protected Object unwrapObject(JavaConstant constant) {
        return snippetReflection.asObject(Object.class, constant);
    }

    private ImageHeapObject createImageHeapObject(JavaConstant constant, Reason reason) {
        assert constant.getJavaKind() == JavaKind.Object && !constant.isNull();

        Optional<JavaConstant> replaced = maybeReplace(constant, reason);
        if (replaced.isPresent()) {
            /*
             * This ensures that we have a unique ImageHeapObject for the original and replaced
             * object. As a side effect, this runs all object transformer again on the replaced
             * constant.
             */
            return toImageHeapObject(replaced.get(), reason);
        }

        if (!hostVM.platformSupported(universe, asObject(constant).getClass())) {
            return null;
        }

        /*
         * Access the constant type after the replacement. Some constants may have types that should
         * not be reachable at run time and thus are replaced.
         */
        AnalysisType type = metaAccess.lookupJavaType(constant);

        ImageHeapObject newImageHeapObject;
        if (type.isArray()) {
            int length = constantReflection.readArrayLength(constant);
            JavaConstant[] arrayElements = new JavaConstant[length];
            for (int idx = 0; idx < length; idx++) {
                arrayElements[idx] = constantReflection.readArrayElement(constant, idx);
            }
            newImageHeapObject = new ImageHeapArray(constant, type, arrayElements);
            markTypeInstantiated(type, newImageHeapObject);
        } else {
            Map<ResolvedJavaField, AnalysisFuture<JavaConstant>> instanceFieldValues = new HashMap<>();
            /*
             * We need to have the new ImageHeapInstance early so that we can reference it in the
             * lambda when the field value gets reachable. But it must not be published to any other
             * thread before all instanceFieldValues are filled in.
             */
            newImageHeapObject = new ImageHeapInstance(constant, type, instanceFieldValues);
            /* We are about to query the type's fields, the type must be marked as reachable. */
            markTypeInstantiated(type, newImageHeapObject);
            for (AnalysisField field : type.getInstanceFields(true)) {
                if (type.getInterfaces().length == 1 && type.getInterfaces()[0].isAnnotation() &&
                                Proxy.class.isAssignableFrom(type.getJavaClass())) {
                    if (field.getName().equals("proxyClassLookup")) {
                        continue;
                    }
                }
                ValueSupplier<JavaConstant> rawFieldValue = readHostedFieldValue(field, universe.toHosted(constant));
                instanceFieldValues.put(field, new AnalysisFuture<>(() -> onFieldValueReachable(field, constant, rawFieldValue, reason.attach(newImageHeapObject))));
            }
        }

        /*
         * Following all the array elements and reachable field values can be done asynchronously.
         */
        execute(() -> onObjectReachable(newImageHeapObject, reason));
        return newImageHeapObject;
    }

    JavaConstant onFieldValueReachable(AnalysisField field, JavaConstant receiver, ValueSupplier<JavaConstant> rawValue, Reason reason) {
        AnalysisError.guarantee(field.reachable.get() != null, "Field value is only reachable when field is reachable");

        /*
         * Check if the field value is available. If not, trying to access it is an error. This
         * forces the callers to only trigger the execution of the future task when the value is
         * ready to be materialized.
         */
        AnalysisError.guarantee(rawValue.isAvailable(), "Value not yet available for " + field.format("%H.%n"));

        /* Attempting to materialize the value before it is available may result in an error. */
        JavaConstant transformedValue = transformFieldValue(field, receiver, rawValue.get());

        if (scanningObserver != null) {
            if (transformedValue.getJavaKind() == JavaKind.Object && hostVM.isRelocatedPointer(asObject(transformedValue))) {
                scanningObserver.forRelocatedPointerFieldValue(receiver, field, transformedValue);
            } else if (transformedValue.isNull()) {
                scanningObserver.forNullFieldValue(receiver, field);
            } else {
                AnalysisFuture<ImageHeapObject> objectFuture = markConstantReachable(transformedValue, reason);
                /* Notify the points-to analysis about the scan. */
                if (objectFuture != null) {
                    /* Add the transformed value to the image heap. */
                    scanningObserver.forNonNullFieldValue(receiver, field, objectFuture.ensureDone().object);
                }
            }
        }
        /* Return the transformed value, but NOT the image heap object. */
        return transformedValue;
    }

    @SuppressWarnings("unused")
    protected JavaConstant transformFieldValue(AnalysisField field, JavaConstant receiverConstant, JavaConstant originalValueConstant) {
        return originalValueConstant;
    }

    void onObjectReachable(ImageHeapObject imageHeapObject, Reason reason) {
        imageHeap.add(imageHeapObject.type, imageHeapObject);

        markTypeInstantiated(imageHeapObject.type, imageHeapObject);

        if (imageHeapObject instanceof ImageHeapArray) {
            ImageHeapArray imageHeapArray = (ImageHeapArray) imageHeapObject;
            for (JavaConstant arrayElement : imageHeapArray.rawArrayElementValues) {
                AnalysisFuture<ImageHeapObject> objectFuture = markConstantReachable(arrayElement, reason.attach(imageHeapArray));
                if (scanningObserver != null && imageHeapArray.type.getComponentType().getJavaKind() == JavaKind.Object) {
                    if (objectFuture == null) {
                        scanningObserver.forNullArrayElement(imageHeapArray.object, imageHeapArray.type, 0);
                    } else {
                        ImageHeapObject element = objectFuture.ensureDone();
                        AnalysisType elementType = constantType(element.object);
                        markTypeInstantiated(elementType, null);
                        /* Process the array element. */
                        scanningObserver.forNonNullArrayElement(imageHeapArray.object, imageHeapArray.type, element.object, elementType, 0);
                    }
                }
            }

        } else {
            ImageHeapInstance imageHeapInstance = (ImageHeapInstance) imageHeapObject;
            for (AnalysisField field : imageHeapObject.type.getInstanceFields(true)) {
                if (field.reachable.get() != null && field.isRead() && isValueAvailable(field)) {
                    execute(imageHeapInstance.getFieldTask(field));
                }
            }
        }
    }

    public boolean isValueAvailable(@SuppressWarnings("unused") AnalysisField field) {
        return true;
    }

    protected String formatReason(String message, Reason reason) {
        return message + ' ' + reason;
    }

    protected ValueSupplier<JavaConstant> readHostedFieldValue(AnalysisField field, JavaConstant object) {
        assert !field.isStatic() || !initializeAtRunTime(field.getDeclaringClass());
        // Wrap the hosted constant into a substrate constant
        JavaConstant value = universe.lookup(hostedConstantReflection.readFieldValue(field.wrapped, object));
        return ValueSupplier.eagerValue(value);
    }

    public void scanEmbeddedRoot(JavaConstant root, BytecodePosition position) {
        AnalysisMethod method = (AnalysisMethod) position.getMethod();
        AnalysisType type = metaAccess.lookupJavaType(root);
        type.registerAsReachable();
        markConstantReachable(root, new MethodScan(method, position));
    }

    /** Trigger rescanning of constants. */
    public void rescanObject(Object object) {
        if (object == null) {
            return;
        }
        if (object instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) object;
            map.forEach((k, v) -> {
                toImageHeapObject(asConstant(k));
                toImageHeapObject(asConstant(v));
            });
        } else if (object instanceof EconomicMap) {
            toImageHeapObject(asConstant(object));
            MapCursor<?, ?> cursor = ((EconomicMap<?, ?>) object).getEntries();
            while (cursor.advance()) {
                toImageHeapObject(asConstant(cursor.getKey()));
                toImageHeapObject(asConstant(cursor.getValue()));
            }
        } else {
            toImageHeapObject(asConstant(object));
        }
    }

    public void scanHub(AnalysisType type) {
        metaAccess.lookupJavaType(java.lang.Class.class).registerAsReachable();
        /* We scan the original class here, the scanner does the replacement to DynamicHub. */
        createImageHeapObject(asConstant(type.getJavaClass()), Reason.HUB);
    }

    protected AnalysisType analysisType(Object constant) {
        return metaAccess.lookupJavaType(constant.getClass());
    }

    protected AnalysisType constantType(JavaConstant constant) {
        return metaAccess.lookupJavaType(constant);
    }

    protected Object asObject(JavaConstant constant) {
        return snippetReflection.asObject(Object.class, constant);
    }

    public JavaConstant asConstant(Object object) {
        return snippetReflection.forObject(object);
    }

    public void cleanupAfterAnalysis() {
        scanningObserver = null;
    }

    /**
     * Marker interface to track why a certain element is reachable, instantiated, invoked, ...
     */
    public interface Reason {
        OtherReason HUB = new OtherReason("Hub");
        OtherReason SCAN = new OtherReason("Scan");
        OtherReason FORCE_SCAN = new OtherReason("ForceScan");
        OtherReason GET_OR_SCAN = new OtherReason("DirectAccess");

        default ReasonChain attach(Reason next) {
            return new ReasonChain(this, next);
        }
    }

    static class ReasonChain implements Reason {
        final Reason previous;
        final Reason current;

        ReasonChain(Reason previous, Reason current) {
            this.previous = previous;
            this.current = current;
        }

        @Override
        public String toString() {
            return previous.toString() + " -> " + current.toString();
        }
    }

    static class OtherReason implements Reason {
        final String reason;

        OtherReason(String reason) {
            this.reason = reason;
        }

        @Override
        public String toString() {
            return reason;
        }
    }

    protected static class FieldScan implements Reason {
        final AnalysisField field;

        FieldScan(AnalysisField field) {
            this.field = field;
        }

        public AnalysisField getField() {
            return field;
        }

        @Override
        public String toString() {
            return field.format("%H.%n");
        }
    }

    static class ArrayScan implements Reason {
        final AnalysisType arrayType;

        ArrayScan(AnalysisType arrayType) {
            this.arrayType = arrayType;
        }

        @Override
        public String toString() {
            return arrayType.toJavaName(true);
        }
    }

    public static class MethodScan implements Reason {
        final AnalysisMethod method;
        final BytecodePosition sourcePosition;

        public MethodScan(AnalysisMethod method, BytecodePosition nodeSourcePosition) {
            this.method = method;
            this.sourcePosition = nodeSourcePosition;
        }

        public AnalysisMethod getMethod() {
            return method;
        }

        @Override
        public String toString() {
            return sourcePosition == null ? method.format("%H.%n(%p)") : method.asStackTraceElement(sourcePosition.getBCI()).toString();
        }
    }

    public void execute(Runnable task) {
        ((PointsToAnalysis) universe.getBigbang()).postTask(debug -> task.run());
    }
}
