package com.oracle.graal.pointsto.meta;

import java.util.Map;

import com.oracle.graal.pointsto.util.AnalysisFuture;

import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.ResolvedJavaField;

/**
 * Additional data for a {@link AnalysisType type} that is only available for types that are marked
 * as reachable. Computed lazily once the type is seen as reachable.
 */
public final class TypeData {
    /** The class initialization state: initialize the class at build time or at run time. */
    final boolean initializeAtRunTime;
    /**
     * The raw values of all static fields, regardless of field reachability status. Evaluating the
     * {@link AnalysisFuture} runs
     * com.oracle.graal.pointsto.heap.ImageHeapScanner#onFieldValueReachable adds the result to the
     * image heap}.
     */
    final Map<ResolvedJavaField, AnalysisFuture<JavaConstant>> rawStaticFieldValues;

    public TypeData(boolean initializeAtRunTime, Map<ResolvedJavaField, AnalysisFuture<JavaConstant>> rawStaticFieldValues) {
        this.initializeAtRunTime = initializeAtRunTime;
        this.rawStaticFieldValues = rawStaticFieldValues;
    }

    public boolean initializeAtRunTime() {
        return initializeAtRunTime;
    }

    public AnalysisFuture<JavaConstant> getStaticFieldValueTask(AnalysisField field) {
        return rawStaticFieldValues.get(field);
    }

    public JavaConstant readStaticFieldValue(AnalysisField field) {
        return rawStaticFieldValues.get(field).ensureDone();
    }

}
