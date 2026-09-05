package example;

import com.cloudhopper.smpp.SmppBindType;
import com.cloudhopper.smpp.SmppConstants;
import com.cloudhopper.smpp.SmppSession;
import com.cloudhopper.smpp.SmppSessionConfiguration;
import com.cloudhopper.smpp.impl.DefaultSmppClient;
import com.cloudhopper.smpp.impl.DefaultSmppSessionHandler;
import com.cloudhopper.smpp.pdu.DeliverSm;
import com.cloudhopper.smpp.pdu.PduRequest;
import com.cloudhopper.smpp.pdu.PduResponse;
import com.cloudhopper.smpp.pdu.SubmitSm;
import com.cloudhopper.smpp.pdu.SubmitSmResp;
import com.cloudhopper.smpp.type.Address;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Binds to Cellophane, sends one OTP, waits for the operator's delivery receipt. */
public class SendOtp {

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("CELLOPHANE_SMPP_PORT", "2775"));
        CountDownLatch receipt = new CountDownLatch(1);

        SmppSessionConfiguration config = new SmppSessionConfiguration();
        config.setType(SmppBindType.TRANSCEIVER);
        config.setHost(System.getenv().getOrDefault("CELLOPHANE_HOST", "localhost"));
        config.setPort(port);
        config.setSystemId("cellophane");
        config.setPassword("cellophane");
        config.getLoggingOptions().setLogPdu(false);

        DefaultSmppClient client = new DefaultSmppClient();
        SmppSession session = client.bind(config, new DefaultSmppSessionHandler() {
            @Override
            @SuppressWarnings("rawtypes")
            public PduResponse firePduRequestReceived(PduRequest request) {
                if (request instanceof DeliverSm dlr) {
                    System.out.println("deliver_sm: " + new String(dlr.getShortMessage(), StandardCharsets.ISO_8859_1));
                    receipt.countDown();
                }
                return request.createResponse();
            }
        });
        System.out.println("bound as transceiver on port " + port);

        SubmitSm sm = new SubmitSm();
        sm.setSourceAddress(new Address((byte) 5, (byte) 0, "MyApp"));
        sm.setDestAddress(new Address((byte) 1, (byte) 1, "8801711111111"));
        sm.setRegisteredDelivery(SmppConstants.REGISTERED_DELIVERY_SMSC_RECEIPT_REQUESTED);
        sm.setShortMessage("Your OTP is 482913".getBytes(StandardCharsets.ISO_8859_1));

        SubmitSmResp resp = session.submit(sm, 5000);
        System.out.println("submit_sm_resp status " + resp.getCommandStatus() + " message_id " + resp.getMessageId());

        if (!receipt.await(10, TimeUnit.SECONDS)) {
            System.out.println("no delivery receipt within 10s");
        }
        session.unbind(2000);
        client.destroy();
    }
}
