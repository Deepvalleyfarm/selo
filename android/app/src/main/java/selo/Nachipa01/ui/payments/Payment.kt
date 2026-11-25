package selo.Nachipa01.ui.payments

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import selo.Nachipa01.domain.Fees
import selo.Nachipa01.domain.Operator
import selo.Nachipa01.domain.Validation
import selo.Nachipa01.ui.components.KvRow
import selo.Nachipa01.ui.theme.Selo

/** Square operator mark on a white tile with a hairline border (compact rows). */
@Composable
fun OperatorMark(op: Operator, size: Dp = 40.dp) {
    Box(Modifier.size(size).shadow(1.dp, RoundedCornerShape(size * .24f)).clip(RoundedCornerShape(size * .24f)).background(Color.White)
        .border(1.dp, Selo.Line, RoundedCornerShape(size * .24f)), contentAlignment = Alignment.Center) {
        Image(painterResource(op.mark), op.label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

/** Full operator lockup on a clean card, shown when a number's operator is detected. */
@Composable
fun OperatorLockup(op: Operator, modifier: Modifier = Modifier, height: Dp = 56.dp) {
    Box(modifier.height(height + 20.dp).clip(RoundedCornerShape(14.dp)).background(Color.White).border(1.dp, Selo.Line, RoundedCornerShape(14.dp))
        .padding(10.dp), contentAlignment = Alignment.Center) {
        Image(painterResource(op.logo), op.label, Modifier.fillMaxHeight(), contentScale = ContentScale.Fit)
    }
}

/** Which number pays: the registered number (default) or another number typed by the user. */
class PaymentChoice(registered: String?) {
    val registered = registered
    var useDefault by mutableStateOf(registered != null)
    var other by mutableStateOf("")
    val otherOperator get() = Operator.detect(other)
    /** Normalised +260 number to charge, or null while the entry is incomplete or unsupported. */
    val msisdn: String? get() = if (useDefault) registered else Validation.normaliseMsisdn(other)?.takeIf { Operator.detect(it) != null }
    val operator: Operator? get() = msisdn?.let { Operator.detect(it) }
}

@Composable
fun rememberPaymentChoice(registered: String?, startWithOther: Boolean = false) =
    remember(registered) { PaymentChoice(registered).also { if (startWithOther) it.useDefault = false } }

/**
 * Pay-with picker (PRD v6.2). The registered number is the default; "Pay with another number" detects the operator
 * from the prefix as the user types (097/077 Airtel Money, 096/076 MTN Money, 095/075 Zamtel Money) and shows its logo.
 */
@Composable
fun PaymentMethodPicker(choice: PaymentChoice, title: String = "Pay with", exclude: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title.uppercase(), color = Selo.Red, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = .6.sp)
        val reg = choice.registered
        val regOp = reg?.let { Operator.detect(it) }
        if (reg != null && regOp != null && reg != exclude) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (choice.useDefault) Selo.RedTint else Color.White)
            .border(1.5.dp, if (choice.useDefault) Selo.Red else Selo.Line, RoundedCornerShape(14.dp)).clickable { choice.useDefault = true }.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            OperatorMark(regOp, 44.dp); Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(regOp.label, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                Text("${Validation.maskMsisdn(reg)} · your registered number", color = Selo.Mute, fontSize = 12.sp)
            }
            Text("DEFAULT", color = Selo.GreenDark, fontSize = 9.5.sp, fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.background(Color(0xFFD9F7EC), RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
            RadioButton(choice.useDefault, { choice.useDefault = true }, colors = RadioButtonDefaults.colors(selectedColor = Selo.Red))
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (!choice.useDefault) Selo.RedTint else Color.White)
            .border(1.5.dp, if (!choice.useDefault) Selo.Red else Selo.Line, RoundedCornerShape(14.dp)).clickable { choice.useDefault = false }.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Pay with another number", fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                RadioButton(!choice.useDefault, { choice.useDefault = false }, colors = RadioButtonDefaults.colors(selectedColor = Selo.Red))
            }
            if (!choice.useDefault) {
                val op = choice.otherOperator
                OutlinedTextField(choice.other, { choice.other = it.filter { c -> c.isDigit() || c == '+' || c == ' ' }.take(16) }, Modifier.fillMaxWidth(),
                    label = { Text("Mobile money number") }, placeholder = { Text("e.g. 0977 123 456") }, singleLine = true, shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    leadingIcon = op?.let { { Box(Modifier.padding(start = 8.dp)) { OperatorMark(it, 30.dp) } } },
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Selo.Red, unfocusedContainerColor = Color.White, focusedContainerColor = Color.White))
                when {
                    op != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        OperatorLockup(op, Modifier.weight(1f), 44.dp); Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(op.label, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                            Text(if (choice.msisdn != null) "Number looks good" else "Detected from ${choice.other.filter(Char::isDigit).take(4)}…", color = Selo.Mute, fontSize = 12.sp)
                        }
                    }
                    Operator.unsupported(choice.other) -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.ErrorOutline, null, tint = Selo.Red, modifier = Modifier.size(16.dp))
                        Text("  Not an Airtel, MTN or Zamtel mobile money number.", color = Selo.Red, fontSize = 12.sp)
                    }
                    else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Operator.entries.forEach { OperatorMark(it, 28.dp) }
                        Text("Airtel 097 · MTN 096 · Zamtel 095", color = Selo.Mute, fontSize = 11.5.sp)
                    }
                }
            }
        }
    }
}

/** One line of the automatically calculated amount. */
data class AmountLine(val label: String, val ngwee: Long)

@Composable
fun AmountBreakdown(lines: List<AmountLine>, totalLabel: String = "Amount to pay") {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White).border(1.dp, Selo.Line, RoundedCornerShape(14.dp)).padding(14.dp)) {
        lines.forEach { KvRow(it.label, Fees.formatK(it.ngwee)) }
        HorizontalDivider(color = Selo.Line, modifier = Modifier.padding(vertical = 6.dp))
        KvRow(totalLabel, Fees.formatK(lines.sumOf { it.ngwee }), strong = true, valueColor = Selo.Red)
    }
}
