package org.nd4j.autodiff.gradcheck;

import org.junit.After;
import org.junit.Before;
import org.nd4j.autodiff.samediff.SameDiff;
import org.nd4j.linalg.BaseNd4jTest;
import org.nd4j.linalg.api.buffer.DataBuffer;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.factory.Nd4jBackend;

import java.util.List;

public class BaseGradCheck extends BaseNd4jTest {

    private DataBuffer.Type initialType;

    public BaseGradCheck(Nd4jBackend backend) {
        super(backend);
    }

    @Override
    public char ordering() {
        return 'c';
    }

    @Before
    public void beforeClass() throws Exception {
        Nd4j.create(1);
        initialType = Nd4j.dataType();

        Nd4j.setDataType(DataBuffer.Type.DOUBLE);
        Nd4j.getRandom().setSeed(123);
    }

    @After
    public void after() throws Exception {
        Nd4j.setDataType(initialType);
    }


    public static void check(SameDiff sd, List<String> failed, String msg){
        try {
            //TODO REMOVE THIS ONCE REDUCTION GRADIENTS ARE DONE - IT'S A HACK TO AVOID EXCEPTION: "Cannot get rank from null shape array"
            sd.execAndEndResult();


            boolean ok = GradCheckUtil.checkGradients(sd);
            if(!ok){
                failed.add(msg);
            }
        } catch (Throwable t){
            t.printStackTrace();
            failed.add(msg + " - EXCEPTION: " + t.getMessage());
        }
    }

}
