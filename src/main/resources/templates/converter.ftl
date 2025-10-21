package ${converterPackageName};

import javax.annotation.Resource;

import org.springframework.stereotype.Service;

import com.ly.flight.intl.${projectName}.biz.gateway.ResponseContext;
import com.ly.flight.intl.${projectName}.biz.gateway.annotation.Gateway;
import com.ly.flight.intl.${projectName}.facade.Converter;
import com.ly.flight.intl.${projectName}.facade.converter.BaseConverter;
import com.ly.flight.intl.${projectName}.facade.exception.ConverterException;
import ${constantPath};
import ${facadeMapperPackageName}.${facadeMapperClassName};
${customAllDtoImport}
${customAllVoImport}



/**
* ${converterClassName}.java desc
* @author ${author}
* @version name: ${converterClassName}.java, v1.0 ${date}  ${author}
*/
@Service
@Gateway(name = ${constantClassName}.${constantName})
public class ${converterClassName} extends BaseConverter implements
Converter<${requestDtoClassName}, ${responseDtoClassName}, ${requestVoClassName},  ${responseVoClassName}> {

        @Resource
        private ${facadeMapperClassName} mapper;

        /**
        * Dto 2 vo req vo.
        *
        * @param request
        * @return ReqVO
        * @throws ConverterException
        */
        @Override
        public ${requestVoClassName} dto2vo(${requestDtoClassName} request) throws ConverterException {
            return mapper.dto2vo(request);
        }

        /**
        * vo 2 dto res dto
        *
        * @param vo
        * @return ResDTO
        * @throws ConverterException
        */
        @Override
        public  ${responseDtoClassName} getResponseResult(ResponseContext<${responseVoClassName}> vo) throws ConverterException {
            return getResponseResult(vo,mapper.vo2dto(vo.getResponse()));
        }
}