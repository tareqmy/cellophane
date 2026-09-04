package io.cellophane.smpp;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** SMPP 3.4 {@code command_status} / error codes (spec section 5.1.3). */
public enum CommandStatus {
    ESME_ROK(0x00000000, "No error"),
    ESME_RINVMSGLEN(0x00000001, "Message length is invalid"),
    ESME_RINVCMDLEN(0x00000002, "Command length is invalid"),
    ESME_RINVCMDID(0x00000003, "Invalid command id"),
    ESME_RINVBNDSTS(0x00000004, "Incorrect bind status for given command"),
    ESME_RALYBND(0x00000005, "ESME already in bound state"),
    ESME_RINVPRTFLG(0x00000006, "Invalid priority flag"),
    ESME_RINVREGDLVFLG(0x00000007, "Invalid registered delivery flag"),
    ESME_RSYSERR(0x00000008, "System error"),
    ESME_RINVSRCADR(0x0000000A, "Invalid source address"),
    ESME_RINVDSTADR(0x0000000B, "Invalid destination address"),
    ESME_RINVMSGID(0x0000000C, "Message id is invalid"),
    ESME_RBINDFAIL(0x0000000D, "Bind failed"),
    ESME_RINVPASWD(0x0000000E, "Invalid password"),
    ESME_RINVSYSID(0x0000000F, "Invalid system id"),
    ESME_RCANCELFAIL(0x00000011, "Cancel SM failed"),
    ESME_RREPLACEFAIL(0x00000013, "Replace SM failed"),
    ESME_RMSGQFUL(0x00000014, "Message queue full"),
    ESME_RINVSERTYP(0x00000015, "Invalid service type"),
    ESME_RINVNUMDESTS(0x00000033, "Invalid number of destinations"),
    ESME_RINVDLNAME(0x00000034, "Invalid distribution list name"),
    ESME_RINVDESTFLAG(0x00000040, "Destination flag is invalid"),
    ESME_RINVSUBREP(0x00000042, "Invalid submit with replace request"),
    ESME_RINVESMCLASS(0x00000043, "Invalid esm_class field data"),
    ESME_RCNTSUBDL(0x00000044, "Cannot submit to distribution list"),
    ESME_RSUBMITFAIL(0x00000045, "submit_sm or submit_multi failed"),
    ESME_RINVSRCTON(0x00000048, "Invalid source address TON"),
    ESME_RINVSRCNPI(0x00000049, "Invalid source address NPI"),
    ESME_RINVDSTTON(0x00000050, "Invalid destination address TON"),
    ESME_RINVDSTNPI(0x00000051, "Invalid destination address NPI"),
    ESME_RINVSYSTYP(0x00000053, "Invalid system type field"),
    ESME_RINVREPFLAG(0x00000054, "Invalid replace_if_present flag"),
    ESME_RINVNUMMSGS(0x00000055, "Invalid number of messages"),
    ESME_RTHROTTLED(0x00000058, "Throttling error: ESME has exceeded allowed message limits"),
    ESME_RINVSCHED(0x00000061, "Invalid scheduled delivery time"),
    ESME_RINVEXPIRY(0x00000062, "Invalid message validity period"),
    ESME_RINVDFTMSGID(0x00000063, "Predefined message invalid or not found"),
    ESME_RX_T_APPN(0x00000064, "ESME receiver temporary app error code"),
    ESME_RX_P_APPN(0x00000065, "ESME receiver permanent app error code"),
    ESME_RX_R_APPN(0x00000066, "ESME receiver reject message error code"),
    ESME_RQUERYFAIL(0x00000067, "query_sm request failed"),
    ESME_RINVOPTPARSTREAM(0x000000C0, "Error in the optional part of the PDU body"),
    ESME_ROPTPARNOTALLWD(0x000000C1, "Optional parameter not allowed"),
    ESME_RINVPARLEN(0x000000C2, "Invalid parameter length"),
    ESME_RMISSINGOPTPARAM(0x000000C3, "Expected optional parameter missing"),
    ESME_RINVOPTPARAMVAL(0x000000C4, "Invalid optional parameter value"),
    ESME_RDELIVERYFAILURE(0x000000FE, "Delivery failure"),
    ESME_RUNKNOWNERR(0x000000FF, "Unknown error");

    private static final Map<Integer, CommandStatus> BY_CODE = new HashMap<>();

    static {
        for (CommandStatus s : values()) {
            BY_CODE.put(s.code, s);
        }
    }

    private final int code;
    private final String description;

    CommandStatus(int code, String description) {
        this.code = code;
        this.description = description;
    }

    public int code() {
        return code;
    }

    public String description() {
        return description;
    }

    public static Optional<CommandStatus> of(int code) {
        return Optional.ofNullable(BY_CODE.get(code));
    }

    /** Name for a status code, or the hex value for vendor-specific / unknown codes. */
    public static String describe(int code) {
        return of(code).map(Enum::name).orElseGet(() -> String.format("0x%08X", code));
    }
}
