package selo.Nachipa01.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import selo.Nachipa01.ui.theme.Selo

/* ------------------------------------------------------------------ headers */

@Composable
fun LogoTile(size: Dp = 40.dp) {
    Box(Modifier.size(size).shadow(3.dp, RoundedCornerShape(10.dp)).background(Color.White, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.ShoppingBag, null, tint = Selo.Red, modifier = Modifier.size(size * 0.62f))
    }
}

/** Red band with back arrow, title, subtitle and optional step bar ("Step 2 of 6"). */
@Composable
fun SeloHeader(
    title: String, subtitle: String? = null, onBack: (() -> Unit)? = null,
    steps: Pair<Int, Int>? = null, trailing: @Composable (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth().background(Selo.Red).statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                }
                Spacer(Modifier.width(8.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium)
                if (subtitle != null) Text(subtitle, color = Color.White.copy(alpha = .88f), fontSize = 12.sp)
            }
            trailing?.invoke()
        }
        if (steps != null) {
            Spacer(Modifier.height(12.dp))
            StepBar(steps.first, steps.second)
        }
    }
}

@Composable
fun StepBar(current: Int, total: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(total) { i ->
            Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(4.dp))
                .background(if (i < current) Color.White else Color.White.copy(alpha = .3f)))
        }
    }
}

/* ---------------------------------------------------------------- buttons */

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, color: Color = Selo.Red, enabled: Boolean = true,
                  loading: Boolean = false, sub: String? = null, whyDisabled: String? = null, onClick: () -> Unit) {
    // A greyed-out button is never silent: tapping it explains what is still missing.
    val app = selo.Nachipa01.ui.LocalAppState.current
    val explain = !enabled && !loading && whyDisabled != null
    Button(onClick = { if (explain) app.fail(whyDisabled) else onClick() }, enabled = (enabled || explain) && !loading, modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = RoundedCornerShape(50), colors = ButtonDefaults.buttonColors(containerColor = if (explain) color.copy(alpha = .4f) else color,
            disabledContainerColor = color.copy(alpha = .4f), disabledContentColor = Color.White)) {
        if (loading) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
        else Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text.uppercase(), style = MaterialTheme.typography.labelLarge)
            if (sub != null) Text(sub, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
fun OutlineButton(text: String, modifier: Modifier = Modifier, sub: String? = null, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(50),
        border = BorderStroke(1.5.dp, Selo.Red), colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White, contentColor = Selo.Red)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, style = MaterialTheme.typography.labelLarge)
            if (sub != null) Text(sub, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Selo.Red.copy(alpha = .8f))
        }
    }
}

@Composable
fun BackNextRow(onBack: () -> Unit, onNext: () -> Unit, nextLabel: String = "Next", nextEnabled: Boolean = true, loading: Boolean = false, whyDisabled: String? = "Complete this step first.") {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlineButton("Back", Modifier.weight(1f), onClick = onBack)
        PrimaryButton(nextLabel, Modifier.weight(1.4f), enabled = nextEnabled, loading = loading, whyDisabled = whyDisabled, onClick = onNext)
    }
}

/* ----------------------------------------------------------------- inputs */

@Composable
fun SeloField(
    label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier,
    keyboard: KeyboardType = KeyboardType.Text, secret: Boolean = false, error: String? = null,
    trailing: ImageVector? = null, singleLine: Boolean = true, placeholder: String? = null,
) {
    var reveal by remember { mutableStateOf(false) }
    // Mobile numbers: +260 is fixed in front, the user types only the 9-digit number (leading 0 or 260 are dropped).
    val phone = keyboard == KeyboardType.Phone
    val phoneError = if (phone) MsisdnInput.problem(value) else null
    Column(modifier) {
        OutlinedTextField(
            value = if (phone) MsisdnInput.national(value) else value, onValueChange = { v -> if (phone) onChange(MsisdnInput.full(v)) else onChange(v) },
            label = { Text(label) }, singleLine = singleLine,
            // always visible (a Material prefix hides until focus): users see +260 and type only their number
            leadingIcon = if (phone) { { Text("+260", fontWeight = FontWeight.Bold, color = Selo.Ink, modifier = Modifier.padding(start = 14.dp, end = 2.dp)) } } else null,
            placeholder = (if (phone) "97 123 4567" else placeholder)?.let { { Text(it, color = Selo.Mute) } },
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), isError = (error ?: phoneError) != null,
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            visualTransformation = if (secret && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
            trailingIcon = when {
                secret -> { { IconButton({ reveal = !reveal }) { Icon(if (reveal) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, "Show or hide", tint = Selo.Red) } } }
                trailing != null -> { { Icon(trailing, null, tint = Selo.Red) } }
                else -> null
            },
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Selo.Red, focusedLabelColor = Selo.Red,
                unfocusedContainerColor = Color.White, focusedContainerColor = Color.White, unfocusedBorderColor = Selo.Field),
        )
        (error ?: phoneError)?.let { Text(it, color = Selo.Red, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp, top = 2.dp)) }
    }
}

/** +260 handling for every mobile number input (pure; unit-tested). Field state holds "+260XXXXXXXXX" or "". */
object MsisdnInput {
    /** What the user sees after the fixed +260: up to 9 digits, without a typed leading 0 or 260. */
    fun national(raw: String): String {
        var d = raw.filter(Char::isDigit)
        if (d.startsWith("260") && (raw.trim().startsWith("+") || d.length > 9)) d = d.drop(3)
        if (d.startsWith("0")) d = d.drop(1)
        return d.take(9)
    }
    fun full(raw: String): String = national(raw).let { if (it.isEmpty()) "" else "+260$it" }
    /** Live hint once the number is complete but isn't an Airtel, MTN or Zamtel mobile. */
    fun problem(value: String): String? { val n = national(value)
        return if (n.length == 9 && selo.Nachipa01.domain.Validation.normaliseMsisdn(n) == null) "Use an Airtel (97/77), MTN (96/76) or Zamtel (95/75) number." else null }
}

/** Five boxes driven by a hidden numeric field. */
@Composable
fun PinInput(value: String, onChange: (String) -> Unit, length: Int = 5, reveal: Boolean = false, error: Boolean = false) {
    BasicTextField(
        value = value, onValueChange = { v -> onChange(v.filter { it.isDigit() }.take(length)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        decorationBox = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(length) { i ->
                    val filled = i < value.length
                    Box(Modifier.weight(1f).height(50.dp).background(Color.White, RoundedCornerShape(10.dp))
                        .border(2.dp, if (error) Selo.Red else if (filled || i == value.length) Selo.Red else Selo.Field, RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center) {
                        if (filled) {
                            if (reveal) Text(value[i].toString(), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            else Box(Modifier.size(12.dp).background(Selo.Ink, CircleShape))
                        }
                    }
                }
            }
        },
    )
}

@Composable
fun SeloChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(50)).background(if (selected) Selo.Red else Color.White)
        .border(1.3.dp, Selo.Red, RoundedCornerShape(50)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(label, color = if (selected) Color.White else Selo.Red, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipGroup(options: List<String>, selected: Set<String>, onToggle: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { SeloChip(it, it in selected) { onToggle(it) } }
    }
}

@Composable
fun CheckRow(checked: Boolean, onChange: (Boolean) -> Unit, text: String) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Checkbox(checked, onChange, colors = CheckboxDefaults.colors(checkedColor = Selo.Red), modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, sub: String? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            if (sub != null) Text(sub, color = Selo.Mute, fontSize = 12.sp)
        }
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Selo.Green, checkedThumbColor = Color.White))
    }
}

@Composable
fun RadioCard(selected: Boolean, title: String, sub: String? = null, badge: String? = null, badgeColor: Color = Selo.Red,
              icon: ImageVector? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (selected && icon != null) Selo.RedTint else Color.White)
        .border(1.4.dp, if (selected) Selo.Red else Selo.Line, RoundedCornerShape(12.dp)).clickable(onClick = onClick)
        .padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (badge != null) {
            Box(Modifier.size(34.dp).background(badgeColor, RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
                Text(badge, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
            }
            Spacer(Modifier.width(10.dp))
        }
        if (icon != null) { Icon(icon, null, tint = Selo.Red); Spacer(Modifier.width(10.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            if (sub != null) Text(sub, color = Selo.Mute, fontSize = 12.sp)
        }
        RadioButton(selected, onClick, colors = RadioButtonDefaults.colors(selectedColor = Selo.Red))
    }
}

/* ---------------------------------------------------------------- display */

@Composable
fun SeloCard(modifier: Modifier = Modifier, padding: Dp = 14.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().shadow(2.dp, RoundedCornerShape(14.dp)).background(Color.White, RoundedCornerShape(14.dp)).padding(padding), content = content)
}

enum class Tone { INFO, OK, WARN, BRAND, FUND, GREY }

private fun toneColors(t: Tone): Pair<Color, Color> = when (t) {
    Tone.INFO -> Selo.TealTint to Color(0xFF0B5F58)
    Tone.OK -> Color(0xFFD9F7EC) to Selo.GreenDark
    Tone.WARN -> Selo.AmberTint to Selo.AmberDark
    Tone.BRAND -> Selo.RedTint to Selo.Red
    Tone.FUND -> Selo.PurpleTint to Color(0xFF5B21B6)
    Tone.GREY -> Color(0xFFF1F1F4) to Selo.Mute
}

@Composable
fun NoteBox(text: String, tone: Tone = Tone.INFO, icon: ImageVector? = null) {
    val (bg, fg) = toneColors(tone)
    Row(Modifier.fillMaxWidth().background(bg, RoundedCornerShape(12.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, color = fg, fontSize = 12.5.sp, lineHeight = 17.sp)
    }
}

@Composable
fun StatusChip(text: String, tone: Tone) {
    val (bg, fg) = toneColors(tone)
    Text(text.uppercase(), color = fg, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = .4.sp,
        modifier = Modifier.background(bg, RoundedCornerShape(9.dp)).padding(horizontal = 8.dp, vertical = 3.dp))
}

@Composable
fun KvRow(label: String, value: String, strong: Boolean = false, valueColor: Color = Selo.Ink, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = if (strong) Selo.Ink else Selo.Mute, fontSize = 13.sp, fontWeight = if (strong) FontWeight.ExtraBold else FontWeight.Normal, modifier = Modifier.weight(1f))
        Text(value, color = valueColor, fontSize = 13.sp, fontWeight = if (strong) FontWeight.ExtraBold else FontWeight.Bold,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default, textAlign = TextAlign.End)
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(text.uppercase(), color = Selo.Red, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = .6.sp,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
}

@Composable
fun H(text: String, size: Int = 15) = Text(text, fontSize = size.sp, fontWeight = FontWeight.ExtraBold, color = Selo.Ink)

@Composable
fun Sub(text: String) = Text(text, fontSize = 12.5.sp, color = Selo.Mute, lineHeight = 17.sp)

/** Captured-document tile used in every KYC step. */
@Composable
fun DocTile(label: String, captured: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.height(84.dp).clip(RoundedCornerShape(12.dp))
        .background(if (captured) Brush.linearGradient(listOf(Color(0xFFEEF3FF), Color(0xFFFBF4E8))) else Brush.linearGradient(listOf(Color.White, Color.White)))
        .border(1.5.dp, if (captured) Selo.Green else Selo.Field, RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(8.dp)) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(if (captured) Icons.Outlined.Badge else Icons.Outlined.PhotoCamera, null, tint = if (captured) Selo.Ink else Selo.Red)
            Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, color = if (captured) Selo.Ink else Selo.Mute)
        }
        if (captured) Icon(Icons.Filled.CheckCircle, "Captured", tint = Selo.Green, modifier = Modifier.align(Alignment.BottomEnd).size(18.dp))
    }
}

enum class StepState { DONE, RUNNING, WAITING }

@Composable
fun ChecklistRow(state: StepState, title: String, sub: String? = null) {
    Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        when (state) {
            StepState.DONE -> Box(Modifier.size(24.dp).background(Selo.Green, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(15.dp)) }
            StepState.RUNNING -> CircularProgressIndicator(Modifier.size(24.dp), color = Selo.Red, strokeWidth = 2.5.dp, trackColor = Color(0xFFF3C3CB))
            StepState.WAITING -> Box(Modifier.size(24.dp).border(2.dp, Selo.Field, CircleShape))
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = if (state == StepState.WAITING) Color(0xFFA3A3AD) else Selo.Ink)
            if (sub != null) Text(sub, fontSize = 12.sp, color = Selo.Mute)
        }
    }
}

@Composable
fun LockedNote(text: String) {
    Row(Modifier.fillMaxWidth().background(Color(0xFFF1F1F4), RoundedCornerShape(10.dp)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Lock, null, tint = Selo.Mute, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 12.sp, color = Selo.Mute)
    }
}

@Composable
fun BigBadge(icon: ImageVector, bg: Color, ring: Color, size: Dp = 88.dp) {
    Box(Modifier.size(size + 20.dp).background(ring, CircleShape), contentAlignment = Alignment.Center) {
        Box(Modifier.size(size).background(bg, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(size / 2))
        }
    }
}

@Composable
fun ErrorText(msg: String?) { if (msg != null) Text(msg, color = Selo.Red, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }

/** Scrollable form body used by every wizard screen. */
@Composable
fun FormColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxSize().background(Selo.Surface).verticalScrollable().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable
fun Modifier.verticalScrollable(): Modifier = this.then(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()))
