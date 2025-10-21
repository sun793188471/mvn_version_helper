package ${managerPackageName};

import com.ly.flight.intl.${projectName}.biz.gateway.ResponseContext;
import ${serviceCoreProxyPath};
import ${constantPath};
import com.ly.flight.intl.${projectName}.biz.manager.BaseManager;
import com.ly.flight.intl.${projectName}.biz.gateway.annotation.Gateway;
import org.springframework.stereotype.Service;
import javax.annotation.Resource;
${customAllVoImport}

/**
* ${managerClassName}.java desc
* @author ${author}
* @version name: ${managerClassName}.java, v1.0 ${date}  ${author}
*/
@Service
@Gateway(name = ${constantClassName}.${constantName})
public class ${managerClassName} extends BaseManager implements ${serviceCoreProxyName}<${requestVoClassName}, ${responseVoClassName}> {

    @Override
    public ResponseContext<${responseVoClassName}> invoke(${requestVoClassName} request) {
        ${responseVoClassName} responseVO = new ${responseVoClassName}();
        // todo 业务实现
        // responseVO.setData();
        return createResult(responseVO);
    }
}
