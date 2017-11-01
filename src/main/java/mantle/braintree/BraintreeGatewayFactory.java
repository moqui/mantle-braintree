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
import org.moqui.entity.EntityFacade;
import org.moqui.entity.EntityValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class BraintreeGatewayFactory {
    protected static final Logger logger = LoggerFactory.getLogger(BraintreeGatewayFactory.class);
    private static BraintreeGateway gateway;

    public static BraintreeGateway getInstance(String paymentGatewayConfigId, EntityFacade entity) {
        if (gateway == null) {
            EntityValue gatewayConfig = entity.find("mantle.account.method.PaymentGatewayConfig")
                    .condition("paymentGatewayConfigId", paymentGatewayConfigId)
                    .useCache(true).one();
            if (gatewayConfig != null && gatewayConfig.getString("paymentGatewayTypeEnumId").equals("PgtBraintree")) {
                EntityValue braintreeConfig = gatewayConfig.findRelatedOne("braintree.PaymentGatewayBraintree", false, false);
                if (braintreeConfig != null) {
                    gateway = new BraintreeGateway(
                            "Y".equalsIgnoreCase(braintreeConfig.getString("testMode")) ? Environment.SANDBOX : Environment.PRODUCTION,
                            braintreeConfig.getString("merchantId"),
                            braintreeConfig.getString("publicKey"),
                            braintreeConfig.getString("privateKey")
                    );
                }
            }
        }
        if (gateway == null) logger.error("Braintree accound is not configured properly");
        return gateway;
    }
}
