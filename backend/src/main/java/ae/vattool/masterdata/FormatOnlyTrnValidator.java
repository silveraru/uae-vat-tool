package ae.vattool.masterdata;

import org.springframework.stereotype.Component;

@Component
public class FormatOnlyTrnValidator implements TrnValidator {
    @Override
    public boolean isValid(String trn) {
        return trn != null && trn.matches("[0-9]{15}");
    }
}
