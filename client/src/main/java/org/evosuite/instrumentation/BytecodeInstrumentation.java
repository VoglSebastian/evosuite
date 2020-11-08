/**
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 * <p>
 * This file is part of EvoSuite.
 * <p>
 * EvoSuite is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3.0 of the License, or
 * (at your option) any later version.
 * <p>
 * EvoSuite is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser Public License for more details.
 * <p>
 * You should have received a copy of the GNU Lesser General Public
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.instrumentation;

import BooleanTransformation.BooleanToIntMethodVisitor;
import BooleanTransformation.BooleanToIntTransformer;
import MethodAnalyser.ByteCodeInstructions.ByteCodeInstruction;
import MethodAnalyser.Results.MethodIdentifier;
import org.evosuite.PackageInfo;
import org.evosuite.Properties;
import org.evosuite.assertion.CheapPurityAnalyzer;
import org.evosuite.classpath.ResourceList;
import org.evosuite.coverage.branch.Branch;
import org.evosuite.coverage.branch.BranchPool;
import org.evosuite.coverage.branch.BranchType;
import org.evosuite.graphs.cfg.CFGClassAdapter;
import org.evosuite.instrumentation.error.ErrorConditionClassAdapter;
import org.evosuite.instrumentation.testability.ComparisonTransformation;
import org.evosuite.instrumentation.testability.ContainerTransformation;
import org.evosuite.instrumentation.testability.StringTransformation;
import org.evosuite.junit.writer.TestSuiteWriterUtils;
import org.evosuite.rmi.ClientServices;
import org.evosuite.runtime.RuntimeSettings;
import org.evosuite.runtime.instrumentation.*;
import org.evosuite.runtime.util.ComputeClassWriter;
import org.evosuite.seeding.PrimitiveClassAdapter;
import org.evosuite.setup.DependencyAnalysis;
import org.evosuite.setup.TestCluster;
import org.evosuite.setup.callgraph.CallGraph;
import org.evosuite.setup.callgraph.CallGraphGenerator;
import org.evosuite.statistics.RuntimeVariable;
import org.evosuite.testcarver.instrument.Instrumenter;
import org.evosuite.testcarver.instrument.TransformerUtil;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.SerialVersionUIDAdder;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.util.TraceClassVisitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * The bytecode transformer - transforms bytecode depending on package and
 * whether it is the class under test
 *
 * @author Gordon Fraser
 */
public class BytecodeInstrumentation {

    private static Logger logger = LoggerFactory.getLogger(BytecodeInstrumentation.class);

    private long timeFotTT = 0;
    private int instrumentedClasses = 0;
    private int booleanJumps = 0;
    private int dependentUpdates = 0;

    private final Instrumenter testCarvingInstrumenter;
    private static List<MethodIdentifier> helperMethods = new ArrayList<>();

    /**
     * <p>
     * Constructor for BytecodeInstrumentation.
     * </p>
     */
    public BytecodeInstrumentation() {
        this.testCarvingInstrumenter = new Instrumenter();
    }

    private static String[] getEvoSuitePackages() {
        return new String[]{PackageInfo.getEvoSuitePackage(), "org.exsyst", "de.unisb.cs.st.testcarver",
                "de.unisb.cs.st.evosuite", "testing.generation.evosuite", "de.unisl.cs.st.bugex"};
    }

    /**
     * Check if we can instrument the given class
     *
     * @param className a {@link java.lang.String} object.
     * @return a boolean.
     */
    public static boolean checkIfCanInstrument(String className) {
        return RuntimeInstrumentation.checkIfCanInstrument(className);
    }

    /**
     * Check if we the class belongs to an EvoSuite package
     *
     * @param className a {@link java.lang.String} object.
     * @return a boolean.
     */
    public static boolean checkIfEvoSuitePackage(String className) {
        for (String s : BytecodeInstrumentation.getEvoSuitePackages()) {
            if (className.startsWith(s)) {
                return true;
            }
        }
        return false;
    }

    private CallGraph callGraph;

    /**
     * <p>
     * shouldTransform
     * </p>
     *
     * @param className a {@link java.lang.String} object.
     * @return a boolean.
     */
    public boolean shouldTransform(String className) {
        if (!Properties.TT)
            return false;
        switch (Properties.TT_SCOPE) {
            case ALL:
                logger.info("Allowing transformation of " + className);
                return true;
            case TARGET:
                if (className.equals(Properties.TARGET_CLASS) || className.startsWith(Properties.TARGET_CLASS + "$"))
                    return true;
                break;
            case PREFIX:
                if (className.startsWith(Properties.PROJECT_PREFIX))
                    return true;
                break;
            case CALL_TREE:
                if (className.equals(Properties.TARGET_CLASS) || className.startsWith(Properties.TARGET_CLASS + "$"))
                    return true;
                if (callGraph == null) callGraph = CallGraphGenerator.analyze(Properties.TARGET_CLASS);
                return (callGraph.isCalledClass(className) && !className.startsWith("java")) ||
                        (className.startsWith(Properties.PROJECT_PREFIX) && !Properties.PROJECT_PREFIX.equals(""));

        }
        logger.info("Preventing transformation of " + className);
        return false;
    }

    private boolean isTargetClassName(String className) {
        // TODO: Need to replace this in the long term
        return TestCluster.isTargetClassName(className);
    }

    /**
     * <p>
     * transformBytes
     * </p>
     *
     * @param className a {@link java.lang.String} object.
     * @param reader    a {@link org.objectweb.asm.ClassReader} object.
     * @return an array of byte.
     */
    public byte[] transformBytes(ClassLoader classLoader, String className, ClassReader reader) {

        int readFlags = ClassReader.SKIP_FRAMES;

        if (Properties.INSTRUMENTATION_SKIP_DEBUG)
            readFlags |= ClassReader.SKIP_DEBUG;

        String classNameWithDots = ResourceList.getClassNameFromResourcePath(className);

        if (!checkIfCanInstrument(classNameWithDots)) {
            throw new RuntimeException("Should not transform a shared class (" + classNameWithDots
                    + ")! Load by parent (JVM) classloader.");
        }

        TransformationStatistics.reset();

        /*
         * To use COMPUTE_FRAMES we need to remove JSR commands. Therefore, we
         * have a JSRInlinerAdapter in NonTargetClassAdapter as well as
         * CFGAdapter.
         */
        int asmFlags = ClassWriter.COMPUTE_FRAMES;
        ClassWriter writer = new ComputeClassWriter(asmFlags);

        ClassVisitor cv = writer;
        if (logger.isDebugEnabled()) {
            cv = new TraceClassVisitor(cv, new PrintWriter(System.err));
        }

        if (Properties.RESET_STATIC_FIELDS) {
            cv = new StaticAccessClassAdapter(cv, className);
        }

        if (Properties.PURE_INSPECTORS) {
            CheapPurityAnalyzer purityAnalyzer = CheapPurityAnalyzer.getInstance();
            cv = new PurityAnalysisClassVisitor(cv, className, purityAnalyzer);
        }

        if (Properties.MAX_LOOP_ITERATIONS >= 0) {
            cv = new LoopCounterClassAdapter(cv);
        }

        // Apply transformations to class under test and its owned classes
        if (DependencyAnalysis.shouldAnalyze(classNameWithDots)) {
            logger.debug("Applying target transformation to class " + classNameWithDots);
            if (!Properties.TEST_CARVING && Properties.MAKE_ACCESSIBLE) {
                cv = new AccessibleClassAdapter(cv, className);
            }

            cv = new RemoveFinalClassAdapter(cv);

            cv = new ExecutionPathClassAdapter(cv, className);

            cv = new CFGClassAdapter(classLoader, cv, className);

            if (Properties.EXCEPTION_BRANCHES) {
                cv = new ExceptionTransformationClassAdapter(cv, className);
            }

            if (Properties.ERROR_BRANCHES) {
                cv = new ErrorConditionClassAdapter(cv, className);
            }

        } else {
            logger.debug("Not applying target transformation");
            cv = new NonTargetClassAdapter(cv, className);

            if (Properties.MAKE_ACCESSIBLE) {
                cv = new AccessibleClassAdapter(cv, className);
            }

            // If we are doing testability transformation on all classes we need
            // to create the CFG first
            if (Properties.TT && classNameWithDots.startsWith(Properties.CLASS_PREFIX)) {
                cv = new CFGClassAdapter(classLoader, cv, className);
            }
        }

        // Collect constant values for the value pool
        cv = new PrimitiveClassAdapter(cv, className);

        if (Properties.RESET_STATIC_FIELDS) {
            cv = handleStaticReset(className, cv);
        }

        // Mock instrumentation (eg File and TCP).
        if (TestSuiteWriterUtils.needToUseAgent()) {
             cv = new MethodCallReplacementClassAdapter(cv, className);

            /*
             * If the class is serializable, then doing any change (adding hashCode, static reset, etc)
             * will change the serialVersionUID if it is not defined in the class.
             * Hence, if it is not defined, we have to define it to
             * avoid problems in serialising the class, as reading Master will not do instrumentation.
             * The serialVersionUID HAS to be the same as the un-instrumented class
             */
            if (RuntimeSettings.applyUIDTransformation)
                cv = new SerialVersionUIDAdder(cv);
        }

        // Testability Transformations
        if (classNameWithDots.startsWith(Properties.PROJECT_PREFIX)
                || (!Properties.TARGET_CLASS_PREFIX.isEmpty()
                && classNameWithDots.startsWith(Properties.TARGET_CLASS_PREFIX))
                || shouldTransform(classNameWithDots)) {

            ClassNode cn = new AnnotatedClassNode();
            reader.accept(cn, readFlags);
            logger.info("Starting transformation of " + className);

            if (Properties.STRING_REPLACEMENT) {
                StringTransformation st = new StringTransformation(cn);
                if (isTargetClassName(classNameWithDots) || shouldTransform(classNameWithDots))
                    cn = st.transform();
            }

            ComparisonTransformation cmp = new ComparisonTransformation(cn);
            if (isTargetClassName(classNameWithDots) || shouldTransform(classNameWithDots)) {
                // cn = cmp.transform();
                ContainerTransformation ct = new ContainerTransformation(cn);
                // cn = ct.transform();
            }

            if (shouldTransform(classNameWithDots)) {
                logger.info("Testability Transforming " + className);
                boolean isTargetClass =
                        classNameWithDots.equals(Properties.TARGET_CLASS) || classNameWithDots.startsWith(Properties.TARGET_CLASS +
                                "$");
                BooleanToIntTransformer tt = new BooleanToIntTransformer(Collections.emptyList(),
                        false, System.out, null,
                        s -> this.shouldTransform(s.replaceAll("/", ".")),
                        Properties.TT_USE_CDG_PATHS, true);
                //new BooleanTestabilityTransformation(cn, classLoader);
                try {
                    String name = cn.name;
                    logger.debug("Going to transform " + cn.name);
                    long startMillis = System.currentTimeMillis();
                    cn = tt.transform(cn, classLoader);
                    long endMillis = System.currentTimeMillis();
                    if (!tt.getFinishedInstrumentation().contains(name)) {
                        throw new IllegalStateException("Did not transform class: " + name);
                    }
                    helperMethods.addAll(tt.getHelperMethods());
                    logger.info("Added {} helper Methods for Class {}" , tt.getHelperMethods().size() ,className);
                    if (!isTargetClass)
                        timeFotTT += endMillis - startMillis;
                    ClientServices.getInstance().getClientNode().trackOutputVariable(RuntimeVariable.TT_TIME, timeFotTT);
                    // if(true) throw new IllegalStateException("... " + cn.methods.stream().map(mn -> mn.name)
                    // .collect(Collectors.joining(" ")));
                    int booleanBranchingConditions = tt.getFlagJumps().size();
                    int dependentUpdates = tt.getDependentUpdates().size();
                    instrumentedClasses++;
                    this.booleanJumps += booleanBranchingConditions;
                    this.dependentUpdates += dependentUpdates;
                    ClientServices.getInstance().getClientNode().trackOutputVariable(RuntimeVariable.BOOLEAN_JUMPS,
                            booleanJumps);
                    ClientServices.getInstance().getClientNode().trackOutputVariable(RuntimeVariable.DEPENDENT_UPDATES, dependentUpdates);
                    ClientServices.getInstance().getClientNode().trackOutputVariable(RuntimeVariable.INSTRUMENTED_CLASSES,
                            instrumentedClasses);

                    Collection<ByteCodeInstruction> flagJumps = tt.getFlagJumps();
                    Collection<Branch> allBranches = BranchPool.getInstance(classLoader).getAllBranches();
                    for (BooleanToIntMethodVisitor.DependentUpdate dependentUpdate : tt.getDependentUpdates()) {
                        ByteCodeInstruction start = dependentUpdate.getStart();
                    }
                    for (ByteCodeInstruction booleanBranchingCondition : flagJumps) {
                        Collection<Integer> successors = booleanBranchingCondition.getSuccessors();
                        String methodName = booleanBranchingCondition.getMethodName();
                        Set<Branch> collect = allBranches.stream()
                                .filter(b -> b.getMethodName().equals(methodName))
                                .filter(b -> successors.contains(b.getInstruction().getInstructionId()))
                                .collect(Collectors.toSet());
                        collect.forEach(b -> b.addBranchType(BranchType.BOOLEAN_CONDITION));
                    }
                } catch (Throwable t) {
                    throw new Error(t);
                }
                logger.info("Testability Transformation done: " + className);
            } else {
                ClientServices.getInstance().getClientNode().trackOutputVariable(RuntimeVariable.TT_TIME, timeFotTT);
                ClientServices.getInstance().getClientNode().trackOutputVariable(RuntimeVariable.BOOLEAN_JUMPS,
                        booleanJumps);
                ClientServices.getInstance().getClientNode().trackOutputVariable(RuntimeVariable.DEPENDENT_UPDATES, dependentUpdates);
                ClientServices.getInstance().getClientNode().trackOutputVariable(RuntimeVariable.INSTRUMENTED_CLASSES,
                        instrumentedClasses);
            }

            // -----
            cn.accept(cv);

            if (Properties.TEST_CARVING && TransformerUtil.isClassConsideredForInstrumentation(className)) {
                return handleCarving(className, writer);
            }

        } else {
            reader.accept(cv, readFlags);
        }

        ClientServices.getInstance().getClientNode().trackOutputVariable(RuntimeVariable.TT_TIME, timeFotTT);
        ClientServices.getInstance().getClientNode().trackOutputVariable(RuntimeVariable.BOOLEAN_JUMPS,
                booleanJumps);
        ClientServices.getInstance().getClientNode().trackOutputVariable(RuntimeVariable.DEPENDENT_UPDATES, dependentUpdates);
        ClientServices.getInstance().getClientNode().trackOutputVariable(RuntimeVariable.INSTRUMENTED_CLASSES,
                instrumentedClasses);
        return writer.toByteArray();
    }

    private byte[] handleCarving(String className, ClassWriter writer) {
        ClassReader cr = new ClassReader(writer.toByteArray());
        ClassNode cn2 = new ClassNode();
        cr.accept(cn2, ClassReader.EXPAND_FRAMES);

        this.testCarvingInstrumenter.transformClassNode(cn2, className);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn2.accept(cw);

        if (logger.isDebugEnabled()) {
            final StringWriter sw = new StringWriter();
            cn2.accept(new TraceClassVisitor(new PrintWriter(sw)));
            logger.debug("test carving instrumentation result:\n{}", sw);
        }

        return cw.toByteArray();
    }

    public static boolean coverMethod(String method){

        boolean b =
                helperMethods.stream().map(i -> i.getInternalClassName().replaceAll("/",".")
                        +"."+i.getMethodName()+i.getMethodDescriptor()).noneMatch(m -> m.equals(method));
        MethodIdentifier id = null;
        if(!b){
            id =
                    helperMethods.stream().filter(i -> (i.getInternalClassName().replaceAll("/",".")
                            +"."+i.getMethodName()+i.getMethodDescriptor()).equals(method)).findFirst().get();
        }
        return b;
    }

    /**
     * Adds the instrumentation to deal with re-iniatilizing classes: adding
     * __STATIC_RESET() methods, inserting callbacks for PUTSTATIC and GETSTATIC
     * instructions
     *
     * @param className
     * @param cv
     * @return
     */
    private static ClassVisitor handleStaticReset(String className, ClassVisitor cv) {
        // Create a __STATIC_RESET() cloning the original <clinit> method or
        // create one by default
        final CreateClassResetClassAdapter resetClassAdapter;
        if (Properties.RESET_STATIC_FINAL_FIELDS) {
            resetClassAdapter = new CreateClassResetClassAdapter(cv, className, true);
        } else {
            resetClassAdapter = new CreateClassResetClassAdapter(cv, className, false);
        }
        cv = resetClassAdapter;

        // Adds a callback before leaving the <clinit> method
        EndOfClassInitializerVisitor exitClassInitAdapter = new EndOfClassInitializerVisitor(cv, className);
        cv = exitClassInitAdapter;
        return cv;
    }

}
