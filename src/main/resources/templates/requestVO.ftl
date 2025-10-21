<#assign baseVOType = "BaseVO<Long>">
package ${requestVoPackageName};

import com.ly.flight.intl.reversebase.common.vo.BaseVO;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;


/**
* ${requestVoClassName}.java desc
* @author ${author}
* @version name: ${requestVoClassName}.java, v1.0 ${date}  ${author}
*/
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class ${requestVoClassName} extends ${baseVOType} {

    private static final long serialVersionUID = -1;
}