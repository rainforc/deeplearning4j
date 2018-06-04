package org.nd4j.autodiff.validation;

import com.google.common.collect.ImmutableSet;
import com.google.common.reflect.ClassPath;
import lombok.extern.slf4j.Slf4j;
import org.nd4j.autodiff.functions.DifferentialFunction;
import org.nd4j.autodiff.samediff.SDVariable;
import org.nd4j.autodiff.samediff.SameDiff;
import org.nd4j.imports.converters.DifferentialFunctionClassHolder;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.api.ops.DynamicCustomOp;
import org.nd4j.linalg.api.ops.impl.accum.All;
import org.nd4j.linalg.api.ops.impl.accum.Any;
import org.nd4j.linalg.api.ops.impl.accum.EqualsWithEps;
import org.nd4j.linalg.api.ops.impl.broadcast.*;
import org.nd4j.linalg.api.ops.impl.indexaccum.FirstIndex;
import org.nd4j.linalg.api.ops.impl.indexaccum.IAMax;
import org.nd4j.linalg.api.ops.impl.indexaccum.IAMin;
import org.nd4j.linalg.api.ops.impl.indexaccum.LastIndex;
import org.nd4j.linalg.api.ops.impl.layers.convolution.Col2Im;
import org.nd4j.linalg.api.ops.impl.shape.ConfusionMatrix;
import org.nd4j.linalg.api.ops.impl.shape.Eye;
import org.nd4j.linalg.api.ops.impl.shape.OneHot;
import org.nd4j.linalg.api.ops.impl.transforms.BinaryMinimalRelativeError;
import org.nd4j.linalg.api.ops.impl.transforms.Histogram;
import org.nd4j.linalg.api.ops.impl.transforms.LegacyDropOut;
import org.nd4j.linalg.api.ops.impl.transforms.LegacyDropOutInverted;
import org.nd4j.linalg.api.ops.random.compat.RandomStandardNormal;
import org.nd4j.linalg.api.ops.random.custom.DistributionUniform;
import org.nd4j.linalg.api.ops.random.impl.*;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.function.Function;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.util.*;

@Slf4j
public class OpValidation {

    /**
     *
     * @param testCase
     * @return NULL if test passes, or error message otherwise
     */
    public static String validate(TestCase testCase){
        testCase.assertConfigValid();

        //First: collect coverage information
        collectCoverageInformation(testCase);


        //Check forward pass:
        if(testCase.fwdTestFns() != null && testCase.fwdTestFns().size() > 0){
            SameDiff sd = testCase.sameDiff();
            try {
                sd.exec();
            } catch (Exception e){
                throw new RuntimeException("Error during forward pass testing" + testCase.testNameErrMsg(), e);
            }

            for(Map.Entry<String,Function<INDArray,String>> e : testCase.fwdTestFns().entrySet()){
                SDVariable v = sd.getVariable(e.getKey());
                if(v == null){
                    throw new IllegalStateException("Test case has expected result function defined for variable \"" +
                            e.getKey() + "\" but SameDiff instance does not have a variable for this name" + testCase.testNameErrMsg());
                }

                INDArray actual = v.getArr();
                if(actual == null){
                    throw new IllegalStateException("Null INDArray after forward pass for variable \"" + e.getKey() + "\"");
                }

                String error;
                try{
                    error = e.getValue().apply(actual);
                } catch (Throwable t){
                    throw new IllegalStateException("Error checking forward pass for variable \"" + e.getKey() + "\": exception was" +
                            " thrown by foward pass validation function", t);
                }

                if(error != null){
                    return error;
                }
            }
        }

        //Check gradients:
        if(testCase.gradientCheck()){
            boolean ok;
            try{
                ok = GradCheckUtil.checkGradients(testCase);
            } catch (Throwable t){
                throw new IllegalStateException("Exception encountered during gradient check" + testCase.testNameErrMsg(), t);
            }

            if(!ok){
                return "Gradient check failed" + testCase.testNameErrMsg();
            }
        }

        return null;    //OK - passed
    }


    public static String validate(OpTestCase testCase){
        collectCoverageInformation(testCase);

        //Check shape function:
        List<long[]> outShapes;
        try{
            outShapes = Nd4j.getExecutioner().calculateOutputShape(testCase.op());
        } catch (Throwable t){
            throw new IllegalStateException("Error calculating output shapes during op validation", t);
        }

        if(outShapes.size() != testCase.testFns().size()){
            return "Expected number of output shapes and number of outputs differ. " + outShapes.size() + " output shapes," +
                    " but OpTestCase specifies " + testCase.testFns().size() + " outputs expected";
        }

        for( int i=0; i<outShapes.size(); i++ ){
            long[] act = outShapes.get(i);
            long[] exp = testCase.expShapes().get(i);
            if(!Arrays.equals(exp, act)){
                return "Shape function check failed for output " + i + ": expected shape " + Arrays.toString(exp) +
                        ", actual shape " + Arrays.toString(act);
            }
        }

        //Check the outputs:
        try {
            Nd4j.getExecutioner().exec(testCase.op());
        } catch (Throwable t){
            throw new IllegalStateException("Error during op execution", t);
        }

        for( int i=0; i<testCase.testFns().size(); i++ ){
            String error;
            try{
                error = testCase.testFns().get(i).apply(testCase.op().outputArguments()[i]);
            } catch (Throwable t){
                throw new IllegalStateException("Exception thrown during op output validation for output " + i, t);
            }

            if(error != null){
                return error;
            }
        }

        return null;    //OK
    }



    //==================================================================================================================
    // Coverage information

    private static List<Class> allOps;
    private static Map<Class,Integer> gradCheckCoverageCountPerClass = new LinkedHashMap<>();
    private static Map<Class,Integer> fwdPassCoverageCountPerClass = new LinkedHashMap<>();
    private static Map<Class,Integer> singleOpTestCountPerClass = new LinkedHashMap<>();


    private static void collectCoverageInformation(TestCase testCase){
        SameDiff sd = testCase.sameDiff();

        //NOTE: Count on a per-test-case basis, not on a 'per function seen' basis
        //i.e., don't double count if a SameDiff instance has multiple copies of the same op type

        //Collect coverage information for backprop:
        DifferentialFunction[] functions = sd.functions();
        Set<Class> backpropSeen = new HashSet<>();
        for(DifferentialFunction df : functions){
            backpropSeen.add(df.getClass());
        }
        for(Class c : backpropSeen){
            gradCheckCoverageCountPerClass.put(c, gradCheckCoverageCountPerClass.get(c) + 1);
        }

        //Collect coverage information for forward pass (expected outputs)
        Set<Class> seen = null;
        if(testCase.fwdTestFns() != null){
            for(String s : testCase.fwdTestFns().keySet()){
                //Determine the differential function that this variable is the output of, if any
                DifferentialFunction df = sd.getVariableOutputFunction(s);
                if(df != null){
                    if(seen == null)
                        seen = new HashSet<>();

                    seen.add(df.getClass());
                }
            }
        }

        if(seen != null){
            for(Class c : seen){
                fwdPassCoverageCountPerClass.put(c, fwdPassCoverageCountPerClass.get(c)+1);
            }
        }
    }

    private static void collectCoverageInformation(OpTestCase testCase){
        //TODO we're basically assuming subtypes of DynamicCustomOp here, for coverage... not DCO itself
        singleOpTestCountPerClass.put(testCase.op().getClass(),
                singleOpTestCountPerClass.get(testCase.op().getClass()) + 1);
    }

    //Collect coverage information
    static {
        initializeCoverage();
    }

    private static void initializeCoverage(){
        //Scan classpath to find all DifferentialFunction instances, so tensorflow/onnx mappings can be made
        //We're assuming here that all instances with such mappings are defined in ND4J
        //As of 04/2018 all DifferentialFunction classes are defined in org.nd4j.linalg.api.ops - with the exception
        // of ILossFunction instances, which don't have TF/Onnx import working anyway
        ImmutableSet<ClassPath.ClassInfo> info;
        try {
            //Dependency note: this ClassPath class was added in Guava 14
            info = com.google.common.reflect.ClassPath.from(DifferentialFunctionClassHolder.class.getClassLoader())
                    .getTopLevelClassesRecursive("org.nd4j.linalg.api.ops");
        } catch (IOException e){
            //Should never happen
            throw new RuntimeException(e);
        }


        allOps = new ArrayList<>(gradCheckCoverageCountPerClass.keySet());
        for(ClassPath.ClassInfo c : info){
            //Load method: Loads (but doesn't link or initialize) the class.
            Class<?> clazz;
            try{
                clazz = Class.forName(c.getName());
            } catch (ClassNotFoundException e){
                //Should never happen as  this was found on the classpath
                throw new RuntimeException(e);
            }


            if (Modifier.isAbstract(clazz.getModifiers()) || clazz.isInterface() || !DifferentialFunction.class.isAssignableFrom(clazz))
                continue;

            if(DifferentialFunction.class.isAssignableFrom(clazz) && !clazz.getSimpleName().contains("Old")){   //Exclude OldSubOp, etc
                allOps.add(clazz);
            }
        }


        Collections.sort(allOps, new Comparator<Class>() {
            @Override
            public int compare(Class o1, Class o2) {
                return o1.getName().compareTo(o2.getName());
            }
        });
        for(Class c : allOps){
            gradCheckCoverageCountPerClass.put(c, 0);
            fwdPassCoverageCountPerClass.put(c, 0);
            singleOpTestCountPerClass.put(c, 0);
        }
    }

    public static void logCoverageInformation( boolean logSeen, boolean logUnseen ){
        //Set of ops that we can't gradient check
        Set<Class> excludedFromBackpropCoverage = excludedFromGradientCheckCoverage();

        String numFormat = "%3d";
        int countAdequate = 0;
        int countAdequateBwd = 0;
        int countAdequateFwd = 0;
        if(logSeen){
            log.info(" --- Adequately Tested Classes ---");
            for(Class c : allOps){
                int countBackpropSeen = gradCheckCoverageCountPerClass.get(c);
                int countFwdValidation = fwdPassCoverageCountPerClass.get(c);

                if(countBackpropSeen > 0){
                    countAdequateBwd++;
                }
                if(countFwdValidation > 0){
                    countAdequateFwd++;
                }
                if(countFwdValidation > 0 && countBackpropSeen > 0){
                    countAdequate++;
                }

                boolean gradExcluded = excludedFromBackpropCoverage.contains(c);
                if(countFwdValidation > 0 && (countBackpropSeen > 0 || gradExcluded)){
                    //At least 1 forward test, and 1 gradient check

                    if(gradExcluded){
                        log.info("Forward: {} tests, GradCheck: <excluded> for op {}", String.format(numFormat, countFwdValidation), c.getName());
                    } else {
                        log.info("Forward: {} tests, GradCheck: {} tests  for op {}", String.format(numFormat, countFwdValidation),
                                String.format(numFormat, countBackpropSeen), c.getName());
                    }
                }
            }
        }

        if(logUnseen){
            log.info(" --- Classes NOT Tested Adequately ---");
            for(Class c : allOps){
                int countBackpropSeen = gradCheckCoverageCountPerClass.get(c);
                int countFwdValidation = fwdPassCoverageCountPerClass.get(c);

                boolean gradExcluded = excludedFromBackpropCoverage.contains(c);
                if(countFwdValidation == 0 || (countBackpropSeen == 0 && !gradExcluded)){
                    //0 forward test OR 0 gradient check (and not excluded from grad checks)

                    if(gradExcluded){
                        log.info("Forward: {} tests, GradCheck: <excluded> for op {}", String.format(numFormat, countFwdValidation), c.getName());
                    } else {
                        log.info("Forward: {} tests, GradCheck: {} tests  for op {}", String.format(numFormat, countFwdValidation),
                                String.format(numFormat, countBackpropSeen), c.getName());
                    }
                }
            }
        }


        int totalFwd = allOps.size();
        int totalBwd = 0;
        for(Class c : allOps){
            if(!isBackpropOp(c)){
                totalBwd++;
            }
        }

        double fracFwdAdequate = countAdequateFwd / (double)totalFwd;
        double fracBwdAdequate = countAdequateBwd / (double)totalBwd;
        double fracAdequate = countAdequate / (double)allOps.size();
        String pcFwd = String.format("%.2f",fracFwdAdequate*100.0);
        String pcBwd = String.format("%.2f",fracBwdAdequate*100.0);
        String pc = String.format("%.2f",fracAdequate*100.0);


        log.info("*****************************************************");
        log.info("Op Validation:        {} of {} classes with adequate tests ({}% coverage)", countAdequate, allOps.size(), pc);
        log.info("Forward pass tests:   {} of {} classes ({}% coverage)", countAdequateFwd, totalFwd, pcFwd);
        log.info("Gradient check tests: {} of {} classes ({}% coverage)", countAdequateBwd, totalBwd, pcBwd);
        log.info("({} ops excluded from gradient check coverage information)", excludedFromBackpropCoverage.size());
        log.info("*****************************************************");
    }

    private static boolean isBackpropOp(Class<?> c){
        String name = c.getSimpleName();
        return name.contains("Bp") || name.contains("Derivative") || name.contains("Grad");
    }

    /**
     * Returns a list of classes that are not gradient checkable.
     * An operation may not be gradient checkable due to, for example:
     * (a) Having no real-valued arguments<br>
     * (b) Having random output (dropout, for example)<br>
     *
     * Note that hawving non-real-valued output is OK - we still want to test these, as they
     * should pass back zero gradients!
     */
    private static Set<Class> excludedFromGradientCheckCoverage(){
        List list = Arrays.asList(
                //Exclude misc
                DynamicCustomOp.class,
                EqualsWithEps.class,
                ConfusionMatrix.class,
                Eye.class,
                OneHot.class,
                BinaryMinimalRelativeError.class,
                BinaryMinimalRelativeError.class,
                Histogram.class,
                //Exclude manual broadcast ops: SameDiff uses auto broadcasting
                BroadcastAMax.class,
                BroadcastAMax.class,
                BroadcastAddOp.class,
                BroadcastCopyOp.class,
                BroadcastDivOp.class,
                BroadcastEqualTo.class,
                BroadcastGreaterThan.class,
                BroadcastGreaterThanOrEqual.class,
                BroadcastLessThan.class,
                BroadcastLessThanOrEqual.class,
                BroadcastMax.class,
                BroadcastMin.class,
                BroadcastMulOp.class,
                BroadcastNotEqual.class,
                BroadcastRDivOp.class,
                BroadcastRSubOp.class,
                BroadcastSubOp.class,
                //Exclude boolean operations:
                Any.class,
                All.class,
                //Exclude index accumulations (index out, not real-valued)
                FirstIndex.class,
                IAMax.class,
                IAMin.class,
                LastIndex.class,
                //Exclude Random ops
                LegacyDropOut.class,
                LegacyDropOutInverted.class,
                RandomStandardNormal.class,
                DistributionUniform.class,
                AlphaDropOut.class,
                BernoulliDistribution.class,
                BinomialDistribution.class,
                BinomialDistributionEx.class,
                Choice.class,
                DropOut.class,
                DropOutInverted.class,
                GaussianDistribution.class,
                LogNormalDistribution.class,
                ProbablisticMerge.class,
                Range.class,
                TruncatedNormalDistribution.class,
                UniformDistribution.class,
                //Other ops we don't intend to be differentiable (only used as part of backprop, etc)
                Col2Im.class
        );

        return new HashSet<>(list);
    }

}
