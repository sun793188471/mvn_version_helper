<#assign baseVOType = "BaseVO<Long>">
<#assign BaseRequestDTO = "BaseRequestDTO">

package ${facadeImplPackageName};

import javax.annotation.Resource;

import org.perf4j.aop.Profiled;
import org.springframework.stereotype.Service;

import com.ly.flight.intl.reversebase.common.aop.log.GatewayLogCfg;
import com.ly.flight.intl.reversebase.common.vo.BaseVO;
import ${constantPath};
import com.ly.flight.intl.${projectName}.facade.${facadeInterfaceClassName};
import ${serviceProxyPath};
import com.ly.flight.intl.${projectName}.facade.response.BaseResponseDTO;
import com.ly.sof.facade.base.BaseRequestDTO;
${customAllDtoImport}


/**
* ${facadeImplClassName}.java desc
* @author ${author}
* @version name: ${facadeImplClassName}.java, v1.0 ${date}  ${author}
*/
@Service("${facadeServiceName}")
public class ${facadeImplClassName} implements ${facadeInterfaceClassName} {

    @Resource
    private ${serviceProxyName}<${BaseRequestDTO}, BaseResponseDTO, ${baseVOType}, ${baseVOType}> ${serviceProxy};

    /**
    *
    * @param requestDTO
    * @return
    */
    @Override
    @GatewayLogCfg(logModule = ${constantClassName}.${constantName})
    public ${responseDtoClassName} ${methodName}(${requestDtoClassName} requestDTO) {
       ${responseDtoClassName} responseDTO = new ${responseDtoClassName}();
        responseDTO = (${responseDtoClassName}) ${serviceProxy}.invoke(${constantClassName}.${constantName},
        requestDTO, responseDTO);
        return responseDTO;
    }
}
