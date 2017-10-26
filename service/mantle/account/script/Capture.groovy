/*
 * Copyright 2017 RidgeCrest Herbals. All Rights Reserved.
 */
import com.braintreegateway.BraintreeGateway
import com.braintreegateway.Result
import com.braintreegateway.ValidationError
import com.braintreegateway.ValidationErrors
import com.braintreegateway.Transaction
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
    ec.message.addError("Cannot capture payment ${paymentId}, not a Braintree payment.")
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
    Result result = gateway.transaction().submitForSettlement(paymentRefNum)
    if (!result.isSuccess()) {
        for (ValidationError error : result.getErrors().getAllDeepValidationErrors()) {
            ec.logger.error("Error ${error.attribute} (code ${error.code}) ${error.message}")
            ec.message.addError(error.message)
            ec.service.sync().name("mantle.account.BraintreeServices.save#BraintreeResponse").parameters([
                    paymentGatewayConfigId:"RchBraintree", paymentOperationEnumId:"PgoCapture",
                    reasonCode:"${error.code}", reasonMessage: "${error.message}", payment:payment, amount:amount,
                    referenceNum:transactionId, successIndicator: 'N']).call()
        }
        return
    }

    Transaction transaction = result.getTarget()
    String transactionId = transaction.getId()

    // the transaction is settled
    ec.service.sync().name("mantle.account.BraintreeServices.save#BraintreeResponse").parameters([
            paymentGatewayConfigId:"RchBraintree", paymentOperationEnumId:"PgoCapture",
            payment:payment, amount:amount, referenceNum:transactionId, successIndicator: 'Y']).call()

} catch (NotFoundException e) {
    ec.message.addError("Transaction ${paymentRefNum} not found")
    return
}