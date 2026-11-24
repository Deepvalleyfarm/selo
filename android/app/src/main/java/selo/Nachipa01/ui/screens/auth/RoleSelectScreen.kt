package selo.Nachipa01.ui.screens.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import selo.Nachipa01.data.model.Role
import selo.Nachipa01.ui.components.*
import selo.Nachipa01.ui.theme.Selo

private val roleInfo = listOf(
    Triple(Role.BUYER, Icons.Outlined.ShoppingCart, listOf("Browse products", "Watch videos", "Purchase goods", "Save favourites")),
    Triple(Role.SELLER, Icons.Outlined.Storefront, listOf("Create listings", "Sell products", "Manage orders", "Grow business")),
    Triple(Role.AGENT, Icons.Outlined.Groups, listOf("Sell for sellers", "Earn commission", "Manage inventory", "Receive payments")),
    Triple(Role.RIDER, Icons.Outlined.TwoWheeler, listOf("Accept deliveries", "Track routes", "Complete deliveries", "Get paid per trip")),
)

/** A2: one role per mobile number. */
@Composable
fun RoleSelectScreen(onBack: () -> Unit, onContinue: (Role) -> Unit) {
    var role by remember { mutableStateOf(Role.BUYER) }
    Column(Modifier.fillMaxSize()) {
        SeloHeader("Create Profile", "Choose your role", onBack = onBack)
        FormColumn {
            H("How will you use SeloNaChipa?")
            Sub("Pick one. Each mobile number can hold only one role.")
            roleInfo.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { (r, icon, bullets) ->
                        val on = r == role
                        Box(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(if (on) Selo.RedTint else Color.White)
                            .border(1.8.dp, if (on) Selo.Red else Selo.Line, RoundedCornerShape(14.dp)).clickable { role = r }.padding(12.dp)) {
                            Column {
                                Box(Modifier.size(38.dp).background(if (on) Selo.Red else Selo.RedTint, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                                    Icon(icon, null, tint = if (on) Color.White else Selo.Red)
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(r.label, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                                bullets.forEach { Text(it, color = Selo.Mute, fontSize = 12.sp) }
                            }
                            if (on) Box(Modifier.align(Alignment.TopEnd).size(20.dp).background(Selo.Red, CircleShape), contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(13.dp))
                            }
                        }
                    }
                }
            }
            NoteBox("Sellers, agents and riders are verified by our team within 24–48 hours before they can trade or deliver.")
            PrimaryButton("Continue as ${role.label}") { onContinue(role) }
        }
    }
}
