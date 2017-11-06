/*
 * This software is in the public domain under CC0 1.0 Universal plus a
 * Grant of Patent License.
 *
 * To the extent possible under law, the author(s) have dedicated all
 * copyright and related and neighboring rights to this software to the
 * public domain worldwide. This software is distributed without any
 * warranty.
 *
 * You should have received a copy of the CC0 Public Domain Dedication
 * along with this software (see the LICENSE.md file). If not, see
 * <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package mantle.braintree;

import com.braintreegateway.BraintreeGateway;
import com.braintreegateway.Environment;
import org.moqui.context.ExecutionContext;
import org.moqui.entity.EntityValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


public class BraintreeGatewayFactory {
    private static final Logger logger = LoggerFactory.getLogger(BraintreeGatewayFactory.class);
    // maybe better not to cache these so conf values get updated and such?
    // private static Map<String, BraintreeGateway> gatewayById = new ConcurrentHashMap<>();

    public static Map<String, String> ccTypes = new HashMap<>();
    static {
        ccTypes.put("Visa", "CctVisa");
        ccTypes.put("MasterCard", "CctMastercard");
        ccTypes.put("American Express", "CctAmericanExpress");
        ccTypes.put("Discover", "CctDiscover");
        /* see: https://developers.braintreepayments.com/reference/response/credit-card/java#card_type
        American Express
        Carte Blanche
        China UnionPay
        Diners Club
        Discover
        JCB
        Laser
        Maestro
        MasterCard
        Solo
        Switch
        Visa
        Unknown
         */
    }

    public static BraintreeGateway getInstance(String paymentGatewayConfigId, ExecutionContext ec) {
        if (paymentGatewayConfigId == null || paymentGatewayConfigId.isEmpty()) return null;
        BraintreeGateway gateway = null; // gatewayById.get(paymentGatewayConfigId);
        if (gateway == null) {
            EntityValue gatewayConfig = ec.getEntity().find("mantle.account.method.PaymentGatewayConfig")
                    .condition("paymentGatewayConfigId", paymentGatewayConfigId)
                    .useCache(true).one();
            if (gatewayConfig != null && gatewayConfig.getString("paymentGatewayTypeEnumId").equals("PgtBraintree")) {
                EntityValue braintreeConfig = gatewayConfig.findRelatedOne("braintree.PaymentGatewayBraintree", true, false);
                if (braintreeConfig != null) {
                    gateway = new BraintreeGateway(
                            "Y".equalsIgnoreCase(braintreeConfig.getString("testMode")) ? Environment.SANDBOX : Environment.PRODUCTION,
                            braintreeConfig.getString("merchantId"), braintreeConfig.getString("publicKey"),
                            braintreeConfig.getString("privateKey")
                    );
                    // gatewayById.put(paymentGatewayConfigId, gateway);
                }
            }
        }
        if (gateway == null) logger.error("Braintree account is not configured properly for paymentGatewayConfigId " + paymentGatewayConfigId);
        return gateway;
    }
}
