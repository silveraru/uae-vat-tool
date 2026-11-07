package ae.vattool.banking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ae.vattool.banking.StatementParser.Statement;
import ae.vattool.banking.StatementParser.Txn;
import ae.vattool.platform.web.DomainException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class StatementParserTest {

    static final String MT940 = """
            :20:STMT-001
            :25:AE070331234567890123456
            :28C:00001/001
            :60F:C270101AED10000,00
            :61:2702100210D500,00NTRFAE123456//BANKREF1
            :86:VAT PAYMENT FTA GIBAN AE12 3456
            CONTINUED NARRATIVE
            :61:2702150215C250,50NTRFNONREF
            :86:CUSTOMER RECEIPT
            :62F:C270228AED9750,50
            -
            """;

    static final String CAMT = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.053.001.08">
              <BkToCstmrStmt>
                <Stmt>
                  <Id>STMT-002</Id>
                  <Acct><Id><IBAN>AE070331234567890123456</IBAN></Id><Ccy>AED</Ccy></Acct>
                  <Bal><Tp><CdOrPrtry><Cd>OPBD</Cd></CdOrPrtry></Tp><Amt Ccy="AED">1000.00</Amt><CdtDbtInd>CRDT</CdtDbtInd></Bal>
                  <Bal><Tp><CdOrPrtry><Cd>CLBD</Cd></CdOrPrtry></Tp><Amt Ccy="AED">700.00</Amt><CdtDbtInd>CRDT</CdtDbtInd></Bal>
                  <Ntry>
                    <Amt Ccy="AED">300.00</Amt><CdtDbtInd>DBIT</CdtDbtInd>
                    <BookgDt><Dt>2027-02-10</Dt></BookgDt><ValDt><Dt>2027-02-11</Dt></ValDt>
                    <AcctSvcrRef>SVC-1</AcctSvcrRef>
                    <NtryDtls><TxDtls>
                      <Refs><EndToEndId>E2E-1</EndToEndId></Refs>
                      <RltdPties><Cdtr><Nm>Good Supplier</Nm></Cdtr></RltdPties>
                      <RmtInf><Ustrd>Invoice B-1</Ustrd></RmtInf>
                    </TxDtls></NtryDtls>
                  </Ntry>
                </Stmt>
              </BkToCstmrStmt>
            </Document>
            """;

    private static List<Statement> parse(String s) {
        return StatementParser.parse(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void readsAnMt940Statement() {
        Statement s = parse(MT940).getFirst();

        assertThat(s.format()).isEqualTo("MT940");
        assertThat(s.account()).isEqualTo("AE070331234567890123456");
        assertThat(s.statementRef()).isEqualTo("STMT-001");
        assertThat(s.opening()).isEqualByComparingTo("10000.00");
        assertThat(s.closing()).isEqualByComparingTo("9750.50");
        assertThat(s.txns()).hasSize(2);
        Txn debit = s.txns().get(0);
        assertThat(debit.direction()).isEqualTo("DEBIT");
        assertThat(debit.amount()).isEqualByComparingTo("500.00");
        assertThat(debit.bookedOn()).isEqualTo(LocalDate.parse("2027-02-10"));
        assertThat(debit.reference()).isEqualTo("AE123456");
        assertThat(debit.narrative()).contains("GIBAN AE12 3456", "CONTINUED NARRATIVE");
        assertThat(s.txns().get(1).direction()).isEqualTo("CREDIT");
        assertThat(s.txns().get(1).reference()).isNull();
    }

    @Test
    void anMt940ReversalTurnsTheDirectionAround() {
        Statement s = parse(":20:R\n:25:ACC\n:60F:C270101AED0,00\n:61:2702100210RD100,00NTRFX\n:62F:C270228AED100,00\n").getFirst();

        assertThat(s.txns().getFirst().direction()).isEqualTo("CREDIT");
    }

    @Test
    void anEntryDateAcrossTheNewYearStaysInTheRightYear() {
        Statement s = parse(":20:R\n:25:ACC\n:60F:C261231AED0,00\n:61:2612310102D10,00NTRFX\n:62F:C270102AED0,00\n").getFirst();

        assertThat(s.txns().getFirst().bookedOn()).isEqualTo(LocalDate.parse("2027-01-02"));
    }

    @Test
    void readsACamt053Statement() {
        Statement s = parse(CAMT).getFirst();

        assertThat(s.format()).isEqualTo("CAMT053");
        assertThat(s.account()).isEqualTo("AE070331234567890123456");
        assertThat(s.opening()).isEqualByComparingTo("1000.00");
        Txn t = s.txns().getFirst();
        assertThat(t.direction()).isEqualTo("DEBIT");
        assertThat(t.reference()).isEqualTo("E2E-1");
        assertThat(t.counterparty()).isEqualTo("Good Supplier");
        assertThat(t.narrative()).isEqualTo("Invoice B-1");
        assertThat(t.valueOn()).isEqualTo(LocalDate.parse("2027-02-11"));
    }

    @Test
    void refusesAnythingElse() {
        assertThatThrownBy(() -> parse("hello,world\n1,2\n")).isInstanceOf(DomainException.class).hasMessageContaining("not an MT940 or CAMT.053");
        assertThatThrownBy(() -> parse("<Document><Other/></Document>")).isInstanceOf(DomainException.class).hasMessageContaining("not a CAMT.053");
    }

    @Test
    void refusesAnXmlBombBeforeExpandingIt() {
        String bomb = "<?xml version=\"1.0\"?><!DOCTYPE lolz [<!ENTITY lol \"lol\"><!ENTITY lol2 \"&lol;&lol;\">]><Document>&lol2;</Document>";

        assertThatThrownBy(() -> parse(bomb)).isInstanceOf(DomainException.class);
    }
}
