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
import com.braintreegateway.exceptions.NotFoundException
import mantle.braintree.BraintreeGatewayFactory
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityValue

ExecutionContext ec = context.ec

EntityValue payment = ec.entity.find("mantle.account.payment.Payment").condition('paymentId', paymentId).one()
if (payment == null) { ec.message.addError("Payment ${paymentId} not found"); return }

BraintreeGateway gateway = BraintreeGatewayFactory.getInstance(paymentGatewayConfigId, ec)
if (gateway == null) { ec.message.addError("Could not find Braintree gateway configuration or error connecting"); return }

// NOTE: don't need to make sure this is a Braintree gateway PaymentMethod, this service only called if configured on a gateway record for Braintree

String paymentRefNum = payment.paymentRefNum
if (!paymentRefNum) {
    Map response = ec.service.sync().name("mantle.account.PaymentServices.get#AuthorizePaymentGatewayResponse")
            .parameter("paymentId", paymentId).call()
    paymentRefNum = response.paymentGatewayResponse?.referenceNum
}
if (!paymentRefNum) {
    ec.message.addError("Could not find authorization transaction ID (reference number) for Payment ${paymentId}")
    return
}

// paymentRefNum is Braintree transaction id and we can use it to seattle the payment
try {
    Result result = gateway.transaction().submitForSettlement(paymentRefNum)
    transaction = result.target

    if (result.isSuccess()) {
        // if successful the transaction is settled or waiting to settlement
        String reasonMessage = result.message
        if (reasonMessage != null && reasonMessage.length() > 255) reasonMessage = reasonMessage.substring(0, 255)
        Map createPgrOut = ec.service.sync().name("create#mantle.account.method.PaymentGatewayResponse").parameters([
                paymentGatewayConfigId:paymentGatewayConfigId, paymentOperationEnumId:"PgoCapture", paymentId:paymentId,
                paymentMethodId:payment.paymentMethodId, amountUomId:payment.amountUomId, amount:transaction.amount,
                referenceNum:transaction.id, responseCode:transaction.processorResponseCode, reasonMessage:reasonMessage,
                transactionDate:ec.user.nowTimestamp, resultSuccess:"Y", resultDeclined:"N", resultError:"N",
                resultNsf:"N", resultBadExpire:"N", resultBadCardNumber:"N"]).call()
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

            // TODO: add more detailed flags similar to code in Authorize.groovy, can fail even after authorize!
        } else {
            responseText = result.message
        }

        if (responseText != null && responseText.length() > 255) responseText = responseText.substring(0, 255)
        Map createPgrOut = ec.service.sync().name("create#mantle.account.method.PaymentGatewayResponse").requireNewTransaction(true).parameters([
                paymentGatewayConfigId:paymentGatewayConfigId, paymentOperationEnumId: "PgoCapture", paymentId: paymentId,
                paymentMethodId:payment.paymentMethodId, amountUomId: payment.amountUomId, amount: payment.amount,
                referenceNum:(transaction?.id ?: paymentRefNum), responseCode:responseCode, reasonMessage:responseText,
                transactionDate:ec.user.nowTimestamp, resultSuccess:"N", resultDeclined: "Y",
                resultError: "N", resultNsf: "N", resultBadExpire:"N", resultBadCardNumber: "N"]).call()
        // out parameter
        paymentGatewayResponseId = createPgrOut.paymentGatewayResponseId

        List validationErrors = result.errors.allDeepValidationErrors
        if (validationErrors) validationErrors.each({ error -> ec.message.addValidationError(null, error.attribute, null, "${error.message} [${error.code}]", null) })
    }

} catch (NotFoundException e) {
    ec.message.addError("Transaction ${paymentRefNum} not found")
} catch (Exception ge) {
    ec.message.addError("Braintree exception: ${ge.toString()}")
}
