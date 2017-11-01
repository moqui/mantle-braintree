/*
 * Copyright 2017 RidgeCrest Herbals. All Rights Reserved.
 */
import com.braintreegateway.BraintreeGateway
import com.braintreegateway.Result
import com.braintreegateway.ValidationError
import com.braintreegateway.exceptions.NotFoundException
import mantle.braintree.BraintreeGatewayFactory
import org.moqui.entity.EntityValue

BraintreeGateway gateway = BraintreeGatewayFactory.getInstance(paymentGatewayConfigId, ec.entity)

EntityValue payment = ec.entity.find("mantle.account.payment.Payment").condition('paymentId', paymentId).one()
if (!payment) {
    ec.message.addError("Payment ${paymentId} not found");
    return
}

EntityValue paymentMethod = payment.findRelatedOne("method", false, false)
if ("PmtBraintreeAccount" != paymentMethod.paymentMethodTypeEnumId) {
    ec.message.addError("Cannot capture payment ${paymentId}, not a Braintree payment.")
    return
}

String paymentRefNum = payment.paymentRefNum
if (!paymentRefNum) {
    response = ec.service.sync().name("mantle.account.PaymentServices.get#AuthorizePaymentGatewayResponse")
            .parameters([paymentId: paymentId, paymentMethodId:paymentMethod.paymentMethodId]).call()
    paymentRefNum = response.paymentGatewayResponse.referenceNum
}
if (!paymentRefNum) {
    ec.message.addError("Could not find authorization transaction ID (reference number) for Payment ${paymentId}")
    return
}

// paymentRefNum is Braintree transaction id and we can use it to seattle the payment
try {
    Result result = gateway.transaction().submitForSettlement(paymentRefNum)
    if (result.isSuccess()) {
        // if successful the transaction is settled or waiting to settlement
        transaction = result.target

        ec.service.sync().name("create#mantle.account.method.PaymentGatewayResponse").parameters([
                paymentGatewayConfigId:"RchBraintree", paymentOperationEnumId:"PgoCapture", paymentId:paymentId,
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
                    paymentGatewayConfigId: "RchBraintree", paymentOperationEnumId: "PgoCapture", paymentId: paymentId,
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