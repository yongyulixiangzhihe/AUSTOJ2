package cn.edu.aust.judger.service;

import cn.edu.aust.judger.core.Compiler;
import cn.edu.aust.judger.core.Preprocessor;
import cn.edu.aust.judger.core.Runner;
import cn.edu.aust.judger.model.Checkpoint;
import cn.edu.aust.judger.proto.JudgeServerGrpc;
import cn.edu.aust.judger.proto.judgeRequest;
import cn.edu.aust.judger.proto.judgeResponse;
import cn.edu.aust.judger.util.Constant;
import cn.edu.aust.judger.util.LanguageUtil;
import cn.edu.aust.judger.util.PosCode;
import com.google.common.collect.Lists;
import io.grpc.ServerServiceDefinition;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.List;

/**
 * 判题服务,目前是最简单的同步阻塞
 * @author Niu Li
 * @since 2017/2/19
 */
@Slf4j
public class JudgeService extends JudgeServerGrpc.JudgeServerImplBase {

    //后期使用依赖注入替换掉
    private Preprocessor preprocessor = new Preprocessor();
    //后期使用依赖注入替换掉
    private Runner runner = new Runner();
    //后期使用依赖注入替换掉
    private Compiler compiler = new Compiler();
    /**
     * RPC处理判题的服务
     * @param request 判题请求
     * @param responseObserver 判题结果
     */
    @Override
    public void judge(judgeRequest request, StreamObserver<judgeResponse> responseObserver) {
        judgeResponse.Builder response = judgeResponse.newBuilder();
        //该次编译所在目录
        String tempWorkDir = Constant.baseWorkDirectory+ File.separator+request.getProblemId()+File.separator;
        //获取编译命令
        LanguageUtil.Language language = LanguageUtil.getLanguage(request.getLanguage());
        if (language == null) {
            response.setExitCode(PosCode.NO_LANGUAGE.getStatus()).setRuntimeResult("不支持的语言类型");
            log.warn("不支持的语言类型,该提交的id为: %s",request.getSolutionId());
            responseObserver.onNext(response.build());
            responseObserver.onCompleted();
        }
        //安全检查
        //暂时无
        //保存代码到临时目录
        String sourcePath = null;
        try {
            sourcePath = preprocessor.createTestCode(request.getCodeSource(),tempWorkDir,language);
        } catch (Exception e) {
            e.printStackTrace();
            log.error("保存源码出错",e);
            response.setExitCode(PosCode.SYS_ERROR.getStatus()).setRuntimeResult("系统异常");
            responseObserver.onNext(response.build());
            responseObserver.onCompleted();
        }
        //编译代码
        judgeResponse tempResp = compiler.getCompileResult(compiler.getCompileCommandLine(sourcePath,language),
                compiler.getCompileLogPath(sourcePath));
        if (tempResp != null){
            responseObserver.onNext(tempResp);
            responseObserver.onCompleted();
        }
        //获取测试案例
        List<Checkpoint> checkpoints = Lists.newArrayList();
        try {
            checkpoints = preprocessor.fetchTestPoints(request.getProblemId());
        } catch (Exception e) {
            e.printStackTrace();
            log.error("不存在测试案例",e);
            response.setExitCode(PosCode.NO_TESTCASE.getStatus()).setRuntimeResult("不存在测试案例");
            responseObserver.onNext(response.build());
            responseObserver.onCompleted();
        }
        //执行

        //对比结果

        //返回
    }

    @Override
    public ServerServiceDefinition bindService() {
        return super.bindService();
    }

}