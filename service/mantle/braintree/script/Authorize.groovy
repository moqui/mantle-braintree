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
import com.braintreegateway.Result
import com.braintreegateway.TransactionRequest
import com.braintreegateway.Transaction
import mantle.braintree.BraintreeGatewayFactory
import org.moqui.entity.EntityValue

EntityValue payment = ec.entity.find("mantle.account.payment.Payment").condition('paymentId', paymentId).one()
if (payment == null) { ec.message.addError("Payment ${paymentId} not found") }
EntityValue paymentMethod = payment.'mantle.account.method.PaymentMethod'

BigDecimal amount = payment.amount
EntityValue visit = payment.'moqui.server.Visit'

if (paymentMethod == null) {
    if (nonce) {
        // handle passed nonce but no paymentMethodId, create a PaymentMethod; do in separate TX in case this succeeds but transaction().sale() fails
        Map pmResult = ec.service.sync().name("mantle.braintree.BraintreeServices.create#PaymentMethodFromNonce").requireNewTransaction(true)
                .parameters([partyId:payment.fromPartyId, nonce:nonce, paymentId:paymentId]).call()
        if (ec.message.hasError()) return
        paymentMethodId = pmResult.paymentMethodId
        paymentMethod = ec.entity.find('mantle.account.method.PaymentMethod').condition('paymentMethodId', paymentMethodId).one()
    } else {
        ec.message.addError("To authorize must specify an existing payment method ID or a Braintree nonce")
        return
    }
} else {
    paymentMethodId = paymentMethod.paymentMethodId
}

String partyId = paymentMethod?.ownerPartyId ?: payment.fromPartyId

// if no gatewayCimId, store the PaymentMethod in the Vault
if (!paymentMethod.gatewayCimId) {
    ec.service.sync().name("mantle.braintree.BraintreeServices.store#CustomerPaymentMethod")
            .parameters([paymentMethodId:paymentMethodId, paymentId:paymentId, validateSecurityCode:validateSecurityCode]).call()
    // get the fresh PaymentMethod record
    paymentMethod = ec.entity.find('mantle.account.method.PaymentMethod').condition('paymentMethodId', paymentMethodId).one()
}
String paymentMethodToken = paymentMethod.gatewayCimId
if (!paymentMethodToken) ec.logger.warn("No gatewayCimId in PaymentMethod ${paymentMethodId}, will attempt with default PM on customer's Braintree account")

// for call through interface that allows no paymentGatewayConfigId parameter
// try getting paymentGatewayConfigId from PaymentMethod first
if (!paymentGatewayConfigId && paymentMethod != null) paymentGatewayConfigId = paymentMethod.paymentGatewayConfigId
/* no point in this, we need to know the Braintree Vault gateway if not on the PaymentMethod: try getting paymentGatewayConfigId from store
   store conf meant to be used in higher level services that call this
if (!paymentGatewayConfigId && productStoreId) {
    EntityValue storePaymentGateway = ec.entity.find("mantle.product.store.ProductStorePaymentGateway")
            .condition("productStoreId", productStoreId).condition("paymentInstrumentEnumId", "PiCreditCard").useCache(true).one()
    if (storePaymentGateway != null) paymentGatewayConfigId = storePaymentGateway.paymentGatewayConfigId
} */
// try getting from BraintreeVaultPaymentGatewayConfigId preference
if (!paymentGatewayConfigId) paymentGatewayConfigId = ec.user.getPreference('BraintreeVaultPaymentGatewayConfigId')
// get gateway
BraintreeGateway gateway = BraintreeGatewayFactory.getInstance(paymentGatewayConfigId, ec)
if (gateway == null) { ec.message.addError("Could not find Braintree gateway configuration or error connecting"); return }

// do the sale transaction request
TransactionRequest txRequest = new TransactionRequest().amount(amount).customerId(partyId).orderId(orderId)
if (nonce) txRequest.paymentMethodNonce(nonce)
txRequest.paymentMethodToken(paymentMethodToken)
// TODO: getting API error, need to research: if (visit != null) { txRequest.riskData().customerIp(visit.clientIpAddress).customerBrowser(visit.initialUserAgent) }

// NOTE: for PayPal one time vaulted transactions need deviceData()? see https://developers.braintreepayments.com/reference/request/transaction/sale/java#device_data
// FUTURE: consider support for settle now with parameter or something: txRequest.options().submitForSettlement(true).done()
// FUTURE: PO number might be useful: https://developers.braintreepayments.com/reference/request/transaction/sale/java#purchase_order_number
// FUTURE: pass shipping address with txRequest.shipping(): https://developers.braintreepayments.com/reference/request/transaction/sale/java#shipping

Result result
try {
    result = gateway.transaction().sale(txRequest)
} catch (Exception ge) {
    ec.message.addError("Braintree exception: ${ge.toString()}")
    return
}

Transaction transaction

if (result.isSuccess()) {
    transaction = result.target

    String avsCode = transaction.avsErrorResponseCode?:"${transaction.avsPostalCodeResponseCode?:'N/A'}:${transaction.avsStreetAddressResponseCode?:'N/A'}"
    String reasonMessage = result.message
    if (reasonMessage != null && reasonMessage.length() > 255) reasonMessage = reasonMessage.substring(0, 255)
    Map createPgrOut = ec.service.sync().name("create#mantle.account.method.PaymentGatewayResponse").parameters([
            paymentGatewayConfigId:paymentGatewayConfigId, paymentOperationEnumId:"PgoAuthorize", paymentId:paymentId,
            paymentMethodId:paymentMethodId, amountUomId:payment.amountUomId, amount:transaction.amount,
            approvalCode:transaction.processorAuthorizationCode, referenceNum:transaction.id,
            responseCode:transaction.processorResponseCode, reasonMessage:reasonMessage,
            transactionDate:ec.user.nowTimestamp, avsResult:avsCode, cvResult:transaction.cvvResponseCode,
            resultSuccess:"Y", resultDeclined:"N", resultError:"N", resultNsf:"N", resultBadExpire:"N",
            resultBadCardNumber:"N"]).call()
    // out parameter
    paymentGatewayResponseId = createPgrOut.paymentGatewayResponseId

} else {
    // if transaction failed then we should use getTransaction() to retrieve transaction object instead of getTarget()
    transaction = result.transaction
    if (transaction != null) {
        status = transaction.status.toString()

        // analyze transaction object to collect information about authorization problem
        responseCode = transaction.processorResponseCode
        responseText = transaction.processorResponseText

        resultSuccess = "N"
        resultDeclined = "N"
        resultError = "N"
        resultNsf = "N"
        resultBadExpire = "N"
        resultBadCardNumber = "N"
        if (status == "PROCESSOR_DECLINED") {
            resultDeclined = "Y"
            if (responseCode == "2001") resultNsf = "Y"
            if (responseCode == "2004") resultBadExpire = "Y"
            if (responseCode == "2005") resultBadCardNumber = "Y"
            ec.logger.warn("Authorization is declined for payment " + paymentId);
        } else {
            responseText = result.message
            resultError = "Y"
            if (status == "PROCESSOR_REJECTED" || status == "GATEWAY_REJECTED") {
                ec.logger.warn("Authorization is rejected for payment " + paymentId);
            } else {
                ec.logger.warn("Unknown authorization error for payment " + paymentId + " : ${result.message} ")
            }
        }
        avsCode = transaction.avsErrorResponseCode?:"${transaction.avsPostalCodeResponseCode?:'N/A'}:${transaction.avsStreetAddressResponseCode?:'N/A'}"
    } else {
        responseText = result.message
    }

    if (responseText != null && responseText.length() > 255) responseText = responseText.substring(0, 255)
    Map createPgrOut = ec.service.sync().name("create#mantle.account.method.PaymentGatewayResponse").parameters([
            paymentGatewayConfigId:paymentGatewayConfigId, paymentOperationEnumId: "PgoAuthorize", paymentId:paymentId,
            paymentMethodId:paymentMethodId, amountUomId:payment.amountUomId, amount:payment?.amount,
            referenceNum:transaction?.id, responseCode:responseCode, reasonMessage:responseText,
            transactionDate:ec.user.nowTimestamp, avsResult:avsCode, cvResult:transaction?.cvvResponseCode,
            resultSuccess:resultSuccess, resultDeclined: resultDeclined, resultError: resultError, resultNsf: resultNsf,
            resultBadExpire:resultBadExpire, resultBadCardNumber: resultBadCardNumber]).call()
    // out parameter
    paymentGatewayResponseId = createPgrOut.paymentGatewayResponseId

    List validationErrors = result.errors.allDeepValidationErrors
    if (validationErrors) validationErrors.each({ error -> ec.logger.warn("Attribute ${error.attribute} causes error: ${error.message} [${error.code}]") })
}
