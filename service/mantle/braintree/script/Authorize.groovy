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
import com.braintreegateway.BraintreeGateway
import com.braintreegateway.CustomerRequest
import com.braintreegateway.Result
import com.braintreegateway.TransactionRequest
import com.braintreegateway.Transaction
import com.braintreegateway.exceptions.NotFoundException
import mantle.braintree.BraintreeGatewayFactory
import org.moqui.entity.EntityValue

/* TODO: support multiple modes:
 - with nonce: store new payment method based on nonce
 - with paymentMethodId: use existing Braintree Vault PaymentMethod
 */


String paymentGatewayConfigId
EntityValue storePaymentGateway = ec.entity.find("mantle.product.store.ProductStorePaymentGateway")
        .condition("productStoreId", productStoreId).condition("paymentInstrumentEnumId", "PiBraintreeAccount").useCache(true).one()
if (storePaymentGateway != null) {
    EntityValue paymentGatewayConfig = storePaymentGateway.findRelatedOne("config", true, false)
    if (paymentGatewayConfig == null) {
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
    ec.logger.info("Braintree customer ${partyId} not found, creating new customer")
    CustomerRequest customerRequest
    // TODO: review overall flow, and why look up PartyDetail only if there is a logged in user?
    if (ec.user.userId) {
        EntityValue party = ec.entity.find("mantle.party.PartyDetail").condition('partyId', partyId).one()
        customerRequest = new CustomerRequest().id(partyId)
                .firstName(party.firstName).lastName(party.lastName).company(party.organizationName)
    } else {
        customerRequest = new CustomerRequest().id(partyId)
                .firstName(firstName).lastName(lastName).company(organization)
    }
    Result customerResult = gateway.customer().create(customerRequest)
    if (!customerResult.isSuccess()) {
        List validationErrors = customerResult.errors.allDeepValidationErrors
        if (validationErrors) {
            validationErrors.each({ error -> ec.message.addValidationError(null, error.attribute, null, "${error.message} [${error.code}]", null) })
        }
        return
    }
}

TransactionRequest request = new TransactionRequest().amount(amount).customerId(partyId).orderId(orderId)

/*
 * Parameter paymentMethodToken may contain a token if user selected one of the payment methods
 * store previously.
 */
if (paymentMethodToken) {
    request.paymentMethodToken(paymentMethodToken)
} else {
    request.paymentMethodNonce(nonce)
    request.options().storeInVaultOnSuccess(true).done()
}

Result result = gateway.transaction().sale(request)

Transaction transaction = null

if (result.isSuccess()) {
    // if successful the transaction is in Authorized state
    ec.service.sync().name("update#mantle.account.payment.Payment")
            .parameters([paymentId:payment.paymentId, statusId:"PmntAuthorized", paymentGatewayConfigId:paymentGatewayConfigId])
            .call()

    transaction = result.target
    payment.paymentGatewayConfigId = paymentGatewayConfigId

    String avsCode = transaction.avsErrorResponseCode?:(transaction.avsPostalCodeResponseCode + ":" + transaction.avsStreetAddressResponseCode)
    ec.service.sync().name("create#mantle.account.method.PaymentGatewayResponse").parameters([
            paymentGatewayConfigId:paymentGatewayConfigId, paymentOperationEnumId:"PgoAuthorize", paymentId:paymentId,
            paymentMethodId:paymentMethodId, amountUomId:payment.amountUomId, amount:transaction.amount,
            referenceNum:transaction.id, responseCode:transaction.processorResponseCode, reasonMessage: result.message,
            transactionDate:ec.user.nowTimestamp, avsResult:avsCode, cvResult:transaction.cvvResponseCode,
            resultSuccess:"Y", resultDeclined:"N", resultError:"N", resultNsf:"N", resultBadExpire:"N",
            resultBadCardNumber:"N"]).call()

} else {
    // report any validation errors and return because in this case transaction object does not exist
    List validationErrors = result.errors.allDeepValidationErrors
    if (validationErrors) {
        validationErrors.each({ error -> ec.message.addValidationError(null, error.attribute, null, "${error.message} [${error.code}]", null) })

        // TODO: reconsider this, review overall flow
        // in no payment method token then we creates a new payment method but if transaction was not successful
        // due to validation then the PaymentMethod stays in inconsistent state (Braintree type but w/o related
        // information) and could not be used for other payments in the future. So, it's better to expire it.
        if (!paymentMethodToken) {
            EntityValue paymentMethod = ec.entity.find('mantle.account.method.PaymentMethod')
                    .condition('paymentMethodId', paymentMethodId).one()
            paymentMethod.thruDate = ec.user.nowTimestamp
            paymentMethod.update()
        }
    }

    transaction = result.target
    if (transaction != null) {
        String status = transaction.status.toString()

        // analyze transaction object to collect information about authorization problem
        String responseCode = transaction.processorResponseCode
        String responseText = transaction.processorResponseText

        String resultSuccess = "N"
        String resultDeclined = "N"
        String resultError = "N"
        String resultNsf = "N"
        String resultBadExpire = "N"
        String resultBadCardNumber = "N"
        if (status == "PROCESSOR_DECLINED") {
            resultDeclined = "Y"
            if (responseCode == "2001") resultNsf = "Y"
            if (responseCode == "2004") resultBadExpire = "Y"
            if (responseCode == "2005") resultBadCardNumber = "Y"
        } else if (status == "PROCESSOR_REJECTED") {
            responseText = result.message
            if (transaction.gatewayRejectionReason) responseText += (" (" + transaction.gatewayRejectionReason.toString() + ")")
            resultError = "Y"
        } else {
            responseText = result.message
            resultError = "Y"
            // Perhaps another possible status is FAILED
            ec.logger.error("Unknown authorization error: ${result.message}")
        }

        String avsCode = transaction.avsErrorResponseCode ?: (transaction.avsPostalCodeResponseCode?:"" + transaction.avsStreetAddressResponseCode?:"")
        ec.service.sync().name("create#mantle.account.method.PaymentGatewayResponse").parameters([
                paymentGatewayConfigId:paymentGatewayConfigId, paymentOperationEnumId: "PgoAuthorize", paymentId: paymentId,
                paymentMethodId:paymentMethodId, amountUomId: payment.amountUomId, amount: transaction.amount,
                referenceNum:transaction.id, responseCode: transaction.processorResponseCode, reasonMessage: responseText,
                transactionDate:ec.user.nowTimestamp, avsResult: avsCode, cvResult: transaction.cvvResponseCode,
                resultSuccess:resultSuccess, resultDeclined: resultDeclined, resultError: resultError, resultNsf: resultNsf,
                resultBadExpire:resultBadExpire, resultBadCardNumber: resultBadCardNumber]).call()
    }
}

// TODO: only do this on success? using request.options().storeInVaultOnSuccess(true) in API
// save information about payment method and token to make make it possible being reused
// during other purchases
if (!paymentMethodToken && transaction != null) {
    EntityValue paymentMethod = ec.entity.find('mantle.account.method.PaymentMethod').condition('paymentMethodId', paymentMethodId).one()

    String instrument = transaction.paymentInstrumentType
    if ("credit_card" == instrument && ec.entity.find('mantle.account.method.CreditCard').condition('paymentMethodId', paymentMethodId).count() == 0) {
        // we create this object event if the transaction was unsuccessful - for history and it's possible
        // to repeat the transaction if we want this in the future
        paymentMethod.imageUrl = transaction.creditCard.imageUrl
        String description = "${transaction.creditCard.cardType} ${transaction.creditCard.maskedNumber}"
        paymentMethod.paymentMethodTypeEnumId = 'PmtCreditCard'
        paymentMethod.description = "${transaction.creditCard.cardType} ${transaction.creditCard.maskedNumber} via Braintree"
        paymentMethod.gatewayCimId = transaction.creditCard.token

        ec.service.sync().name("create#mantle.account.method.CreditCard").parameters([
                paymentMethodId:paymentMethodId, token:token, imageUrl:imageUrl,
                cardNumber:transaction.creditCard.maskedNumber, expireDate:transaction.creditCard.expirationDate
        ]).call()
    } else if ("paypal_account" == instrument && ec.entity.find('mantle.account.method.PayPalAccount').condition('paymentMethodId', paymentMethodId).count() == 0) {
        paymentMethod.imageUrl = transaction.payPalDetails.imageUrl
        paymentMethod.gatewayCimId = transaction.payPalDetails.token
        paymentMethod.description = "${transaction.payPalDetails.payerEmail} via Braintree"
        ec.service.sync().name("create#mantle.account.method.PayPalAccount").parameters([
                paymentMethodId:paymentMethodId, transactionId:id]).call()
    }

    paymentMethod.paymentGatewayConfigId = paymentGatewayConfigId
    paymentMethod.update()
}
