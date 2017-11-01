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
import org.moqui.account.braintree.BraintreeGatewayFactory
import org.moqui.entity.EntityValue

BraintreeGateway gateway = BraintreeGatewayFactory.getInstance(paymentGatewayConfigId, ec.entity)

EntityValue payment = ec.entity.find("mantle.account.payment.Payment").condition('paymentId', paymentId).one()
if (!payment) {
    ec.message.addError("Payment ${paymentId} not found");
    return
}

EntityValue paymentMethod = payment.findRelatedOne("method", false, false)
if ("PmtBraintreeAccount" != paymentMethod.paymentMethodTypeEnumId) {
    ec.message.addError("Cannot release authorization for payment ${paymentId}, not a Braintree payment.")
    return
}

String paymentRefNum = payment.paymentRefNum
if (!paymentRefNum) {
    response = ec.service.sync().name("mantle.account.PaymentServices.get#AuthorizePaymentGatewayResponse")
            .parameters([paymentId: paymentId]).call()
    paymentRefNum = response.paymentGatewayResponse.referenceNum
}
if (!paymentRefNum) {
    ec.message.addError("Could not find authorization transaction ID (reference number) for Payment ${paymentId}")
    return
}

// paymentRefNum is Braintree transaction id and we can use it to seattle the payment
try {
    Result result = gateway.transaction().voidTransaction(paymentRefNum)
    if (result.isSuccess()) {
        transaction = result.target

        ec.service.sync().name("create#mantle.account.method.PaymentGatewayResponse").parameters([
                paymentGatewayConfigId:paymentGatewayConfigId, paymentOperationEnumId:"PgoRelease", paymentId:paymentId,
                paymentMethodId:paymentMethod.paymentMethodId, amountUomId:payment.amountUomId, amount:transaction.amount,
                referenceNum:transaction.id, responseCode:transaction.processorResponseCode, reasonMessage: result.message,
                transactionDate:ec.user.nowTimestamp, resultSuccess:"Y", resultDeclined:"N", resultError:"N",
                resultNsf:"N", resultBadExpire:"N", resultBadCardNumber:"N"]).call()
    } else {
        // report any validation errors and return because in this case transaction object does not exist
        List validationErrors = result.errors.allDeepValidationErrors
        if (validationErrors) {
            validationErrors.each {error ->
                ec.logger.error("Error code ${error.code} - ${error.message} in ${error.attribute}")
                ec.message.addValidationError(null, error.attribute, null, error.message, null)
            }

            return
        }

        transaction = result.transaction

        if (transaction) {
            String status = transaction.status.toString()

            // analyze transaction object to collect information about authorization problem
            String responseCode = transaction.processorResponseCode
            String responseText = transaction.processorResponseText

            ec.service.sync().name("create#mantle.account.method.PaymentGatewayResponse").parameters([
                    paymentGatewayConfigId:paymentGatewayConfigId, paymentOperationEnumId: "PgoRelease", paymentId: paymentId,
                    paymentMethodId:paymentMethod.paymentMethodId, amountUomId: payment.amountUomId, amount: transaction.amount,
                    referenceNum:transaction.id, responseCode: transaction.processorResponseCode, reasonMessage: responseText,
                    transactionDate:ec.user.nowTimestamp, resultSuccess:"N", resultDeclined: "Y",
                    resultError: "N", resultNsf: "N", resultBadExpire:"N", resultBadCardNumber: "N"]).call()
        }
    }

} catch (NotFoundException e) {
    ec.message.addError("Transaction ${paymentRefNum} not found")
    return
}