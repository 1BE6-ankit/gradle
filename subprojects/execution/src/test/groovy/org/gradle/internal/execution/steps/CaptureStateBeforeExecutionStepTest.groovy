/*
 * Copyright 2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.execution.steps

import com.google.common.collect.ImmutableSortedMap
import org.gradle.internal.execution.AfterPreviousExecutionContext
import org.gradle.internal.execution.BeforeExecutionContext
import org.gradle.internal.execution.UnitOfWork
import org.gradle.internal.execution.history.AfterPreviousExecutionState
import org.gradle.internal.fingerprint.CurrentFileCollectionFingerprint
import org.gradle.internal.fingerprint.FileCollectionFingerprint
import org.gradle.internal.fingerprint.impl.AbsolutePathFingerprintingStrategy
import org.gradle.internal.fingerprint.overlap.OverlappingOutputDetector
import org.gradle.internal.fingerprint.overlap.OverlappingOutputs
import org.gradle.internal.hash.ClassLoaderHierarchyHasher
import org.gradle.internal.hash.HashCode
import org.gradle.internal.snapshot.FileSystemSnapshot
import org.gradle.internal.snapshot.ValueSnapshot
import org.gradle.internal.snapshot.ValueSnapshotter
import org.gradle.internal.snapshot.impl.ImplementationSnapshot

import static org.gradle.internal.execution.UnitOfWork.OverlappingOutputHandling.DETECT_OVERLAPS
import static org.gradle.internal.execution.UnitOfWork.OverlappingOutputHandling.IGNORE_OVERLAPS
import static org.gradle.internal.execution.steps.CaptureStateBeforeExecutionStep.Operation.Result

class CaptureStateBeforeExecutionStepTest extends StepSpec {

    def classloaderHierarchyHasher = Mock(ClassLoaderHierarchyHasher)
    def valueSnapshotter = Mock(ValueSnapshotter)
    // Spock 1.3 fails when using the inherited Stub here
    final UnitOfWork work = Mock()
    final AfterPreviousExecutionContext context = Stub()
    def implementationSnapshot = ImplementationSnapshot.of("MyWorkClass", HashCode.fromInt(1234))
    def overlappingOutputDetector = Mock(OverlappingOutputDetector)

    def step = new CaptureStateBeforeExecutionStep(buildOperationExecutor, classloaderHierarchyHasher, valueSnapshotter, overlappingOutputDetector, delegate)

    def "no state is captured when task history is not maintained"() {
        when:
        step.execute(context)
        then:
        assertNoOperation()
        1 * work.isTaskHistoryMaintained() >> false
        1 * delegate.execute(_) >> { BeforeExecutionContext beforeExecution ->
            assert !beforeExecution.beforeExecutionState.present
        }
        0 * _
    }

    def "implementations are snapshotted"() {
        def additionalImplementations = [
            ImplementationSnapshot.of("FirstAction", HashCode.fromInt(2345)),
            ImplementationSnapshot.of("SecondAction", HashCode.fromInt(3456))
        ]

        when:
        step.execute(context)

        then:
        1 * work.visitImplementations(_) >> { UnitOfWork.ImplementationVisitor visitor ->
            visitor.visitImplementation(implementationSnapshot)
            additionalImplementations.each {
                visitor.visitAdditionalImplementation(it)
            }
        }
        interaction { fingerprintInputs() }
        1 * delegate.execute(_) >> { BeforeExecutionContext beforeExecution ->
            def state = beforeExecution.beforeExecutionState.get()
            assert !state.detectedOverlappingOutputs.present
            assert state.implementation == implementationSnapshot
            assert state.additionalImplementations == additionalImplementations
        }
        0 * _

        withOnlyOperation(CaptureStateBeforeExecutionStep.Operation) {
            assert it.descriptor.displayName == "Snapshot inputs and outputs of job ':test' before execution"
            assert it.result == Result.INSTANCE
        }
    }

    def "input properties are snapshotted"() {
        def inputPropertyValue = 'myValue'
        def valueSnapshot = Mock(ValueSnapshot)

        when:
        step.execute(context)
        then:
        1 * work.visitInputProperties(_) >> { UnitOfWork.InputPropertyVisitor visitor ->
            visitor.visitInputProperty("inputString", inputPropertyValue)
        }
        1 * valueSnapshotter.snapshot(inputPropertyValue) >> valueSnapshot
        interaction { fingerprintInputs() }
        1 * delegate.execute(_) >> { BeforeExecutionContext beforeExecution ->
            def state = beforeExecution.beforeExecutionState.get()
            assert !state.detectedOverlappingOutputs.present
            assert state.inputProperties == ImmutableSortedMap.<String, ValueSnapshot>of('inputString', valueSnapshot)
        }
        0 * _

        withOnlyOperation(CaptureStateBeforeExecutionStep.Operation) {
            assert it.descriptor.displayName == "Snapshot inputs and outputs of job ':test' before execution"
            assert it.result == Result.INSTANCE
        }
    }

    def "uses previous input property snapshots"() {
        def inputPropertyValue = 'myValue'
        def valueSnapshot = Mock(ValueSnapshot)
        def afterPreviousExecutionState = Mock(AfterPreviousExecutionState)

        when:
        step.execute(context)
        then:
        context.afterPreviousExecutionState >> Optional.of(afterPreviousExecutionState)
        1 * afterPreviousExecutionState.inputProperties >> ImmutableSortedMap.<String, ValueSnapshot>of("inputString", valueSnapshot)
        1 * afterPreviousExecutionState.outputFileProperties >> ImmutableSortedMap.<String, FileCollectionFingerprint>of()
        1 * work.visitInputProperties(_) >> { UnitOfWork.InputPropertyVisitor visitor ->
            visitor.visitInputProperty("inputString", inputPropertyValue)
        }
        1 * valueSnapshotter.snapshot(inputPropertyValue, valueSnapshot) >> valueSnapshot
        interaction { fingerprintInputs() }
        1 * delegate.execute(_) >> { BeforeExecutionContext beforeExecution ->
            def state = beforeExecution.beforeExecutionState.get()
            assert !state.detectedOverlappingOutputs.present
            assert state.inputProperties == ImmutableSortedMap.<String, ValueSnapshot>of('inputString', valueSnapshot)
        }
        0 * _

        withOnlyOperation(CaptureStateBeforeExecutionStep.Operation) {
            assert it.descriptor.displayName == "Snapshot inputs and outputs of job ':test' before execution"
            assert it.result == Result.INSTANCE
        }
    }

    def "input file properties are fingerprinted"() {
        def fingerprint = Mock(CurrentFileCollectionFingerprint)

        when:
        step.execute(context)

        then:
        1 * work.visitInputFileProperties(_) >> { UnitOfWork.InputFilePropertyVisitor visitor ->
            visitor.visitInputFileProperty("inputFile", "ignored", false, { -> fingerprint })
        }
        interaction { fingerprintInputs() }
        1 * delegate.execute(_) >> { BeforeExecutionContext beforeExecution ->
            def state = beforeExecution.beforeExecutionState.get()
            assert !state.detectedOverlappingOutputs.present
            assert state.inputFileProperties == ImmutableSortedMap.<String, CurrentFileCollectionFingerprint>of('inputFile', fingerprint)
        }
        0 * _

        withOnlyOperation(CaptureStateBeforeExecutionStep.Operation) {
            assert it.descriptor.displayName == "Snapshot inputs and outputs of job ':test' before execution"
            assert it.result == Result.INSTANCE
        }
    }

    def "output file properties are fingerprinted"() {
        def outputFileSnapshot = Mock(FileSystemSnapshot)

        when:
        step.execute(context)

        then:
        1 * work.snapshotOutputsBeforeExecution() >> ImmutableSortedMap.<String, FileSystemSnapshot>of("outputDir", outputFileSnapshot)
        interaction { fingerprintInputs() }
        1 * delegate.execute(_) >> { BeforeExecutionContext beforeExecution ->
            def state = beforeExecution.beforeExecutionState.get()
            assert !state.detectedOverlappingOutputs.present
            assert state.outputFileProperties == ImmutableSortedMap.<String, CurrentFileCollectionFingerprint>of('outputDir', AbsolutePathFingerprintingStrategy.IGNORE_MISSING.emptyFingerprint)
        }
        0 * _

        withOnlyOperation(CaptureStateBeforeExecutionStep.Operation) {
            assert it.descriptor.displayName == "Snapshot inputs and outputs of job ':test' before execution"
            assert it.result == Result.INSTANCE
        }
    }

    def "uses before output snapshot when there are no overlapping outputs"() {
        def afterPreviousExecutionState = Mock(AfterPreviousExecutionState)
        def afterPreviousOutputFingerprint = Mock(FileCollectionFingerprint)
        def outputFileSnapshot = Mock(FileSystemSnapshot)

        when:
        step.execute(context)
        then:
        context.afterPreviousExecutionState >> Optional.of(afterPreviousExecutionState)
        1 * afterPreviousExecutionState.inputProperties >> ImmutableSortedMap.<String, ValueSnapshot>of()
        1 * afterPreviousExecutionState.outputFileProperties >> ImmutableSortedMap.<String, FileCollectionFingerprint>of("outputDir", afterPreviousOutputFingerprint)

        1 * work.snapshotOutputsBeforeExecution() >> ImmutableSortedMap.<String, FileSystemSnapshot>of("outputDir", outputFileSnapshot)
        1 * outputFileSnapshot.accept(_)

        interaction { fingerprintInputs() }
        1 * delegate.execute(_) >> { BeforeExecutionContext beforeExecution ->
            def state = beforeExecution.beforeExecutionState.get()
            assert !state.detectedOverlappingOutputs.present
            assert state.outputFileProperties == ImmutableSortedMap.<String, CurrentFileCollectionFingerprint>of('outputDir', AbsolutePathFingerprintingStrategy.IGNORE_MISSING.emptyFingerprint)
        }
        0 * _

        withOnlyOperation(CaptureStateBeforeExecutionStep.Operation) {
            assert it.descriptor.displayName == "Snapshot inputs and outputs of job ':test' before execution"
            assert it.result == Result.INSTANCE
        }
    }

    def "detects overlapping outputs when instructed"() {
        def afterPreviousExecutionState = Mock(AfterPreviousExecutionState)
        def afterPreviousOutputFingerprint = Mock(FileCollectionFingerprint)
        def afterPreviousOutputFingerprints = ImmutableSortedMap.<String, FileCollectionFingerprint> of("outputDir", afterPreviousOutputFingerprint)
        def beforeExecutionOutputFingerprint = Mock(FileSystemSnapshot)
        def beforeExecutionOutputFingerprints = ImmutableSortedMap.<String, FileSystemSnapshot> of("outputDir", beforeExecutionOutputFingerprint)

        when:
        step.execute(context)
        then:
        context.afterPreviousExecutionState >> Optional.of(afterPreviousExecutionState)
        1 * afterPreviousExecutionState.inputProperties >> ImmutableSortedMap.of()
        1 * afterPreviousExecutionState.outputFileProperties >> afterPreviousOutputFingerprints
        1 * work.snapshotOutputsBeforeExecution() >> beforeExecutionOutputFingerprints

        1 * work.overlappingOutputHandling >> DETECT_OVERLAPS
        1 * overlappingOutputDetector.detect(afterPreviousOutputFingerprints, beforeExecutionOutputFingerprints) >> null

        1 * beforeExecutionOutputFingerprint.accept(_)

        interaction { fingerprintInputs() }
        1 * delegate.execute(_) >> { BeforeExecutionContext beforeExecution ->
            def state = beforeExecution.beforeExecutionState.get()
            assert !state.detectedOverlappingOutputs.present
            assert state.outputFileProperties == ImmutableSortedMap.<String, CurrentFileCollectionFingerprint>of('outputDir', AbsolutePathFingerprintingStrategy.IGNORE_MISSING.emptyFingerprint)
        }
        0 * _

        withOnlyOperation(CaptureStateBeforeExecutionStep.Operation) {
            assert it.descriptor.displayName == "Snapshot inputs and outputs of job ':test' before execution"
            assert it.result == Result.INSTANCE
        }
    }

    def "filters before output snapshot when there are overlapping outputs"() {
        def afterPreviousExecutionState = Mock(AfterPreviousExecutionState)
        def afterPreviousOutputFingerprint = Mock(FileCollectionFingerprint)
        def afterPreviousOutputFingerprints = ImmutableSortedMap.<String, FileCollectionFingerprint> of("outputDir", afterPreviousOutputFingerprint)
        def beforeExecutionOutputFingerprint = Mock(FileSystemSnapshot)
        def beforeExecutionOutputFingerprints = ImmutableSortedMap.<String, FileSystemSnapshot> of("outputDir", beforeExecutionOutputFingerprint)
        def overlappingOutputs = new OverlappingOutputs("outputDir", "overlapping/path")

        when:
        step.execute(context)
        then:
        context.afterPreviousExecutionState >> Optional.of(afterPreviousExecutionState)
        1 * afterPreviousExecutionState.inputProperties >> ImmutableSortedMap.of()
        1 * afterPreviousExecutionState.outputFileProperties >> afterPreviousOutputFingerprints
        1 * work.snapshotOutputsBeforeExecution() >> beforeExecutionOutputFingerprints

        1 * work.overlappingOutputHandling >> DETECT_OVERLAPS
        1 * overlappingOutputDetector.detect(afterPreviousOutputFingerprints, beforeExecutionOutputFingerprints) >> overlappingOutputs

        1 * afterPreviousOutputFingerprint.fingerprints >> [:]
        1 * beforeExecutionOutputFingerprint.accept(_)

        interaction { fingerprintInputs() }
        1 * delegate.execute(_) >> { BeforeExecutionContext beforeExecution ->
            def state = beforeExecution.beforeExecutionState.get()
            assert state.detectedOverlappingOutputs.get() == overlappingOutputs
            assert state.outputFileProperties == ImmutableSortedMap.<String, CurrentFileCollectionFingerprint>of('outputDir', AbsolutePathFingerprintingStrategy.IGNORE_MISSING.emptyFingerprint)
        }
        0 * _

        withOnlyOperation(CaptureStateBeforeExecutionStep.Operation) {
            assert it.descriptor.displayName == "Snapshot inputs and outputs of job ':test' before execution"
            assert it.result == Result.INSTANCE
        }
    }

    void fingerprintInputs() {
        context.afterPreviousExecutionState >> Optional.empty()
        _ * work.visitImplementations(_ as UnitOfWork.ImplementationVisitor) >> { UnitOfWork.ImplementationVisitor visitor ->
            visitor.visitImplementation(implementationSnapshot)
        }
        _ * work.visitInputProperties(_ as UnitOfWork.InputPropertyVisitor)
        _ * work.visitInputFileProperties(_ as UnitOfWork.InputFilePropertyVisitor)
        _ * work.overlappingOutputHandling >> IGNORE_OVERLAPS
        _ * work.snapshotOutputsBeforeExecution() >> ImmutableSortedMap.of()
        _ * work.displayName >> displayName
        _ * work.taskHistoryMaintained >> true
    }

}
