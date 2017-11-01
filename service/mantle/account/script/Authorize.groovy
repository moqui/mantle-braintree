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
        List validationErrors = result.errors.allDeepValidationErrors
        if (validationErrors) {
            validationErrors.each { error ->
                ec.logger.error("Error ${error.code} - ${error.message} in ${error.attribute}")
                ec.message.addValidationError(null, error.attribute, null, error.message, null)
            }
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

Transaction transaction

if (result.isSuccess()) {
    // if successful the transaction is in Authorized state
    ec.service.sync().name("update#mantle.account.payment.Payment")
            .parameters([paymentId:payment.paymentId, statusId:"PmntAuthorized", paymentGatewayConfigId:paymentGatewayConfigId])
            .call()

    transaction = result.target
    payment.paymentGatewayConfigId = paymentGatewayConfigId

    String avsCode = transaction.avsErrorResponseCode?:(transaction.avsPostalCodeResponseCode + transaction.avsStreetAddressResponseCode)
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
        validationErrors.each {error ->
            ec.logger.error("Error ${error.code} - ${error.message} in ${error.attribute}")
            ec.message.addValidationError(null, error.attribute, null, error.message, null)
        }

        // in no payment method token then we creates a new payment method but if transaction was not successful
        // due to validation then the PaymentMethod stays in inconsistent state (Braintree type but w/o related
        // information) and could not be used for other payments in the future. So, it's better to expire it.
        if (!paymentMethodToken) {
            EntityValue paymentMethod = ec.entity.find('mantle.account.method.PaymentMethod')
                    .condition('paymentMethodId', paymentMethodId).one()
            paymentMethod.thruDate = ec.user.nowTimestamp
            paymentMethod.update()
        }

        return
    }

    transaction = result.transaction

    if (transaction) {
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

// save information about payment method and token to make make it possible being reused
// during other purchases
if (!paymentMethodToken && transaction) {
    EntityValue paymentMethod = ec.entity.find('mantle.account.method.PaymentMethod')
            .condition('paymentMethodId', paymentMethodId).one()
    if (ec.entity.find('braintree.BraintreePaymentMethod').condition('paymentMethodId', paymentMethodId).count() == 0) {
        String description
        String imageUrl
        String token
        String instrument = transaction.paymentInstrumentType
        if ("credit_card" == instrument) {
            // we create this object event if the transaction was unsuccessful - for history and it's possible
            // to repeat the transaction if we want this in the future
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
        ec.service.sync().name("create#braintree.BraintreePaymentMethod").parameters([
                paymentMethodId:paymentMethodId, paymentInstrumentType:instrument, description:description,
                token:token, imageUrl:imageUrl
        ]).call()

        paymentMethod.update();
    }
}
