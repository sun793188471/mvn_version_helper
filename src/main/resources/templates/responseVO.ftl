<#assign baseVOType = "Long">

package ${responseVoPackageName};

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import com.ly.flight.intl.reversebase.common.vo.BaseVO;


/**
* ${responseVoClassName}.java desc
* @author ${author}
* @version name: ${responseVoClassName}.java, v1.0 ${date}  ${author}
*/
@Setter
@Getter
@ToString(callSuper = true)
public class ${responseVoClassName} extends BaseVO<${baseVOType}> {

    private static final long serialVersionUID = -1L;

}