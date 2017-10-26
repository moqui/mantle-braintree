/*
 * Copyright 2017 RidgeCrest Herbals. All Rights Reserved.
 */
import com.braintreegateway.BraintreeGateway
import com.braintreegateway.CustomerRequest
import com.braintreegateway.Result
import com.braintreegateway.ValidationError
import com.braintreegateway.TransactionRequest
import com.braintreegateway.Transaction
import com.braintreegateway.exceptions.NotFoundException
import org.moqui.account.braintree.BraintreeGatewayFactory
import org.moqui.entity.EntityValue

String paymentGatewayConfigId
EntityValue storePaymentGateway = ec.entity.find("mantle.product.store.ProductStorePaymentGateway")
        .condition("productStoreId", productStoreId)
        .condition("paymentInstrumentEnumId", "PiBraintreeAccount")
        .useCache(true).one();
if (storePaymentGateway) {
    EntityValue paymentGatewayConfig = storePaymentGateway.findRelatedOne("config", true, false);
    if (!paymentGatewayConfig ) {
        ec.message.addError("Braintree payment gateway config not found for product store ${productStoreId}")
        return
    }
    paymentGatewayConfigId = paymentGatewayConfig.paymentGatewayConfigId
}
BraintreeGateway gateway = BraintreeGatewayFactory.getInstance(paymentGatewayConfigId, ec.entity)

EntityValue payment = ec.entity.find("mantle.account.payment.Payment").condition('paymentId', paymentId).one()
BigDecimal amount = payment.amount

/*
 * We try to find Braintree customer object for the give partyId and create new one if not found.
 */
try {
    // you more interested in does it find the customer or not than in the customer object
    gateway.customer().find(partyId)
} catch (NotFoundException e) {
    ec.logger.info("Braintree customer ${partyId} is not found and we are going to create new one");
    CustomerRequest customerRequest
    if (ec.user.userId) {
        EntityValue party = ec.entity.find("mantle.party.PartyDetail").condition('partyId', partyId).one();
        customerRequest = new CustomerRequest()
                .id(partyId)
                .firstName(party.firstName)
                .lastName(party.lastName)
                .company(party.organizationName)
    } else {
        customerRequest = new CustomerRequest()
                .id(partyId)
                .firstName(firstName)
                .lastName(lastName)
                .company(organization)
    }
    Result customerResult = gateway.customer().create(customerRequest);
    if (!customerResult.isSuccess()) {
        for (ValidationError error : customerResult.errors.allDeepValidationErrors) {
            ec.logger.error("Error ${error.attribute} (code ${error.code}) ${error.message}")
            ec.message.addError(error.message)
        }
        return
    }
}

TransactionRequest request = new TransactionRequest()
        .amount(amount)
        .customerId(partyId)
        .orderId(orderId)

/*
 * Parameter paymentMethodToken may contain a token if user selected one of the payment methods
 * store previously.
 */
if (paymentMethodToken) {
    request.paymentMethodToken(paymentMethodToken)
} else {
    request.paymentMethodNonce(nonce)
    request.options()
            .storeInVaultOnSuccess(true)
            .done()
}

Result result = gateway.transaction().sale(request);
if (!result.isSuccess()) {
    for (ValidationError error : result.getErrors().getAllDeepValidationErrors()) {
        ec.logger.error("Error ${error.attribute} (code ${error.code}) ${error.message}")
        ec.message.addError(error.message)
        ec.service.sync().name("mantle.account.BraintreeServices.save#BraintreeResponse").parameters([
                paymentGatewayConfigId:"RchBraintree", paymentOperationEnumId:"PgoAuthorize",
                reasonCode:"${error.code}", reasonMessage: "${error.message}", payment:payment, amount:amount,
                referenceNum:transactionId, successIndicator: 'N']).call()
    }
    return;
}

// transaction was finished successfully
Transaction transaction = result.getTarget()
String transactionId = transaction.getId()

// save information about payment method and token to make make it possible being reused
// during other purchases
if (!paymentMethodToken) {
    EntityValue paymentMethod = ec.entity.find('mantle.account.method.PaymentMethod')
            .condition('paymentMethodId', paymentMethodId).one()
    if (ec.entity.find('mantle.account.method.braintree.BraintreePaymentMethod').condition('paymentMethodId', paymentMethodId).count() == 0) {
        String description
        String imageUrl
        String token
        String instrument = transaction.paymentInstrumentType
        if ("credit_card" == instrument) {
            token = transaction.creditCard.token
            imageUrl = transaction.creditCard.imageUrl
            description = "${transaction.creditCard.cardType} ${transaction.creditCard.maskedNumber}"
            paymentMethod.description = "Braintree: " + description
            // set up thru date of the payment method according to card expiration
            String expMonth = transaction.creditCard.expirationMonth
            String expYear = transaction.creditCard.expirationYear
            Calendar cal = ec.user.nowCalendar
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            cal.set(Calendar.MONTH, Integer.valueOf(expMonth))
            cal.set(Calendar.YEAR, Integer.valueOf(expYear))
            cal.set(Calendar.DAY_OF_MONTH, 1)
            cal.add(Calendar.DAY_OF_MONTH, -1)
            paymentMethod.thruDate = cal.getTime()
        } else if ("paypal_account" == instrument) {
            token = transaction.payPalDetails.token
            imageUrl = transaction.payPalDetails.imageUrl
            description = "PayPal ${transaction.payPalDetails.payerEmail}"
            paymentMethod.description = "Braintree: " + description;
        }
        ec.service.sync().name("create#mantle.account.method.braintree.BraintreePaymentMethod").parameters([
                paymentMethodId:paymentMethodId, paymentInstrumentType:instrument, description:description,
                token:token, imageUrl:imageUrl
        ]).call()

        paymentMethod.update();
    }
}

/*
 * Store information about this transaction for further use and change the payment status
 */
ec.service.sync().name("mantle.account.BraintreeServices.save#BraintreeResponse").parameters([
        paymentGatewayConfigId:"RchBraintree", paymentOperationEnumId:"PgoAuthorize",
        payment:payment, amount:amount, referenceNum:transactionId, successIndicator: 'Y']).call()

ec.service.sync().name("update#mantle.account.payment.Payment")
        .parameters([paymentId:payment.paymentId, statusId:"PmntAuthorized"]).call();
