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

package org.gradle.instantexecution

import org.gradle.api.Project
import org.gradle.api.internal.project.DefaultProject
import spock.lang.Unroll


class InstantExecutionReportIntegrationTest extends AbstractInstantExecutionIntegrationTest {

    def "always fails on error serializing broken bean"() {

        given:
        buildScript """
            class BrokenSerializable implements java.io.Serializable {
                private void writeObject(java.io.ObjectOutputStream out) throws IOException {
                    throw new RuntimeException("BOOM")
                }
            }

            task myTask {
                inputs.property 'broken', new BrokenSerializable()
            }
        """

        and:
        def expectedProblem = "input property 'broken' of ':myTask': error writing value of type 'BrokenSerializable'"

        when:
        instantFails 'myTask'

        then:
        expectInstantExecutionFailure(InstantExecutionErrorsException, expectedProblem)

        and:
        failure.assertHasCause("BOOM")
        failure.assertHasErrorOutput("Caused by: java.lang.RuntimeException: BOOM")
        !file(".instant-execution-state").allDescendants().any { it.endsWith(".fingerprint") }

        when:
        withDoNotFailOnProblems()
        instantFails 'myTask'

        then:
        expectInstantExecutionFailure(InstantExecutionErrorsException, expectedProblem)

        and:
        failure.assertHasCause("BOOM")
        failure.assertHasErrorOutput("Caused by: java.lang.RuntimeException: BOOM")
        !file(".instant-execution-state").allDescendants().any { it.endsWith(".fingerprint") }
    }

    def "report mixed problems and errors"() {

        expect:
        true // TODO
    }

    def "problems are reported in build failure and html report by default"() {

        given:
        def expectedProblems = withTwoProblemsBuildScript()

        when:
        instantFails 'broken'

        then:
        expectInstantExecutionFailure(InstantExecutionProblemsException, *expectedProblems)

        and:
        true
        // TODO expectNoInstantExecutionConsoleSummary()
        // TODO expectInstantExecutionHtmlReportFor(*expectedProblems)
    }

    def "problems are reported in console summary and html report when failOnProblems is false"() {

        given:
        def expectedProblems = withTwoProblemsBuildScript()

        when:
        instantRun 'broken', "-D${SystemProperties.failOnProblems}=false"

        then:
        noExceptionThrown()

        and:
        // TODO expectInstantExecutionConsoleSummaryFor(*expectedProblems)
        // TODO expectInstantExecutionHtmlReportFor(*expectedProblems)
        expectInstantExecutionProblems(*expectedProblems)
    }

    def "problems are reported in build failure and html report when failOnProblems is false but maxProblems is reached"() {

        given:
        def expectedProblems = withTwoProblemsBuildScript()

        when:
        instantFails 'broken', "-D${SystemProperties.failOnProblems}=false", "-D${SystemProperties.maxProblems}=2"

        then:
        expectInstantExecutionFailure(TooManyInstantExecutionProblemsException, *expectedProblems)

        and:
        true
        // TODO only one problem gets shown in the stacktrace !!!!!
        // TODO expectNoInstantExecutionConsoleSummary()
        // TODO expectInstantExecutionHtmlReportFor(*expectedProblems)
    }

    // TODO add execution time problem
    private List<String> withTwoProblemsBuildScript() {
        buildScript """
            task broken {
                inputs.property 'brokenProperty', project
                inputs.property 'otherBrokenProperty', project
            }
        """
        return [
            "input property 'brokenProperty' of ':broken': cannot serialize object of type '$DefaultProject.name', a subtype of '$Project.name', as these are not supported with instant execution.",
            "input property 'otherBrokenProperty' of ':broken': cannot serialize object of type '$DefaultProject.name', a subtype of '$Project.name', as these are not supported with instant execution.",
        ]
    }

    def "reports project access during execution"() {

        def instantExecution = newInstantExecutionFixture()

        given:
        settingsFile << "rootProject.name = 'root'"
        buildFile << """
            abstract class MyTask extends DefaultTask {
                @TaskAction
                def action() {
                    println("project:${'$'}{project.name}")
                }
            }

            tasks.register("a", MyTask)
            tasks.register("b", MyTask)
        """

        and:
        def expectedProblems = [
            "task `:a` of type `MyTask`: invocation of 'Task.project' at execution time is unsupported.",
            "task `:b` of type `MyTask`: invocation of 'Task.project' at execution time is unsupported."
        ]

        when:
        instantRun "a", "b"

        then:
        output.count("project:root") == 2
        instantExecution.assertStateStored()

        and:
        expectInstantExecutionProblems(*expectedProblems)
        numberOfProblemsWithStacktraceIn(
            resolveInstantExecutionReportDirectory().file("instant-execution-report-data.js")
        ) == 2

        when:
        instantRun "a", "b"

        then:
        output.count("project:root") == 2
        instantExecution.assertStateLoaded()

        and:
        expectInstantExecutionProblems(*expectedProblems)
        numberOfProblemsWithStacktraceIn(
            resolveInstantExecutionReportDirectory().file("instant-execution-report-data.js")
        ) == 2


        when:
        instantRun "a", "b"

        then:
        output.count("project:root") == 2
        instantExecution.assertStateLoaded()

        and:
        expectInstantExecutionProblems(*expectedProblems)
        numberOfProblemsWithStacktraceIn(
            resolveInstantExecutionReportDirectory().file("instant-execution-report-data.js")
        ) == 2
    }

    def "summarizes unsupported properties"() {
        given:
        buildFile << """
            class SomeBean {
                Gradle gradle
                def nested = new NestedBean()
            }

            class NestedBean {
                Gradle gradle
                Project project
            }

            class SomeTask extends DefaultTask {
                private final bean = new SomeBean()

                SomeTask() {
                    bean.gradle = project.gradle
                    bean.nested.gradle = project.gradle
                    bean.nested.project = project
                }

                @TaskAction
                void run() {
                }
            }

            // ensure there are multiple warnings for the same properties
            task a(type: SomeTask)
            task b(type: SomeTask)
            task c(dependsOn: [a, b])
        """

        when:
        withDoNotFailOnProblems()
        instantRun "c"

        then:
        expectInstantExecutionProblems(
            6,
            "field 'gradle' from type 'SomeBean': cannot serialize object of type 'org.gradle.invocation.DefaultGradle', a subtype of 'org.gradle.api.invocation.Gradle', as these are not supported with instant execution.",
            "field 'gradle' from type 'NestedBean': cannot serialize object of type 'org.gradle.invocation.DefaultGradle', a subtype of 'org.gradle.api.invocation.Gradle', as these are not supported with instant execution.",
            "field 'project' from type 'NestedBean': cannot serialize object of type 'org.gradle.api.internal.project.DefaultProject', a subtype of 'org.gradle.api.Project', as these are not supported with instant execution."
        )
    }

    @Unroll
    def "can limit the number of problems to #maxProblems"() {
        given:
        buildFile << """
            class Bean {
                Project p1
                Project p2
                Project p3
            }

            class FooTask extends DefaultTask {
                private final bean = new Bean()

                FooTask() {
                    bean.with {
                        p1 = project
                        p2 = project
                        p3 = project
                    }
                }

                @TaskAction
                void run() {
                }
            }

            task foo(type: FooTask)
        """

        when:
        withDoNotFailOnProblems()
        instantFails "foo", "-Dorg.gradle.unsafe.instant-execution.max-problems=$maxProblems"

        then:
        def expectedProblems = (1..expectedNumberOfProblems).collect {
            "field 'p$it' from type 'Bean': cannot serialize object of type 'org.gradle.api.internal.project.DefaultProject', a subtype of 'org.gradle.api.Project', as these are not supported with instant execution."
        }
        expectInstantExecutionFailure(
            TooManyInstantExecutionProblemsException,
            expectedNumberOfProblems,
            *expectedProblems
        )

        where:
        maxProblems << [0, 1, 2]
        expectedNumberOfProblems = Math.max(1, maxProblems)
    }

    def "can request to not fail on problems"() {
        given:
        buildFile << """
            class Bean { Project p1 }

            class FooTask extends DefaultTask {
                private final bean = new Bean()
                FooTask() { bean.p1 = project }
                @TaskAction void run() {}
            }

            task foo(type: FooTask)
        """

        when:
        instantRun "foo", "-D${SystemProperties.failOnProblems}=false"

        then:
        expectInstantExecutionProblems(
            "field 'p1' from type 'Bean': cannot serialize object of type 'org.gradle.api.internal.project.DefaultProject', a subtype of 'org.gradle.api.Project', as these are not supported with instant execution."
        )
    }
}
