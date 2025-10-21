package ${validatorPackageName};
import org.springframework.stereotype.Service;

import ${constantPath};
import com.ly.flight.intl.${projectName}.biz.gateway.annotation.Gateway;
import com.ly.flight.intl.${projectName}.facade.Validator;
import com.ly.flight.intl.${projectName}.facade.exception.ValidateException;
import com.ly.flight.intl.${projectName}.facade.validator.BaseValidator;
${customRequestDtoImport}


/**
* ${validatorClassName}.java desc
* @author ${author}
* @version name: ${validatorClassName}.java, v1.0 ${date}  ${author}
*/
@Service
@Gateway(name = ${constantClassName}.${constantName})
public class ${validatorClassName} extends BaseValidator implements Validator<${requestDtoClassName}> {
    /**
    * Validate.
    *
    * @param requestDTO the request
    * @throws ValidateException the validate exception
    */
    @Override
    public void validate(${requestDtoClassName} requestDTO) throws ValidateException {

    }
}
