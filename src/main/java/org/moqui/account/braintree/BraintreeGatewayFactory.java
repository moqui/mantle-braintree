/*
 * Copyright 2017 RidgeCrest Herbals. All Rights Reserved.
 */
package org.moqui.account.braintree;

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
