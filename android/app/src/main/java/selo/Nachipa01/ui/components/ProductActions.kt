package selo.Nachipa01.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddShoppingCart
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import selo.Nachipa01.data.model.Listing
import selo.Nachipa01.ui.LocalAppState
import selo.Nachipa01.ui.theme.Selo

/**
 * Add to cart + Buy, used on every product card and detail view.
 * Add to cart stays on the page and shows a confirmation; Buy goes straight to checkout with this item selected.
 */
@Composable
fun ProductActions(l: Listing, modifier: Modifier = Modifier, qty: Int = 1, compact: Boolean = false, onDark: Boolean = false, height: Dp = if (compact) 34.dp else 46.dp) {
    val app = LocalAppState.current
    val ok = app.inStock(l)
    if (!ok) {
        Text("Out of stock", color = if (onDark) Color.White else Selo.Mute, fontWeight = FontWeight.Bold, fontSize = if (compact) 11.sp else 13.sp, modifier = modifier)
        return
    }
    val inCart = app.cart.firstOrNull { it.listing.id == l.id }?.qty ?: 0
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton({
            val notice = app.addToCart(l, qty)
            app.toast.value = notice ?: "Added to cart · ${app.cartCount} item${if (app.cartCount == 1) "" else "s"}"
        }, Modifier.weight(1f).height(height), shape = RoundedCornerShape(50), contentPadding = PaddingValues(horizontal = 6.dp),
            border = androidx.compose.foundation.BorderStroke(1.5.dp, if (onDark) Color.White else Selo.Red),
            colors = ButtonDefaults.outlinedButtonColors(containerColor = if (onDark) Color.Black.copy(.25f) else Color.White, contentColor = if (onDark) Color.White else Selo.Red)) {
            if (!compact) Icon(Icons.Outlined.AddShoppingCart, null, Modifier.size(18.dp))
            Text(if (compact) (if (inCart > 0) "+ Cart ($inCart)" else "+ Cart") else if (inCart > 0) "  Add more ($inCart)" else "  Add to cart",
                fontWeight = FontWeight.Bold, fontSize = if (compact) 11.sp else 14.sp, maxLines = 1)
        }
        Button({ app.buyNow(l) }, Modifier.weight(1f).height(height), shape = RoundedCornerShape(50), contentPadding = PaddingValues(horizontal = 6.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Selo.Red)) {
            if (!compact) Icon(Icons.Outlined.ShoppingBag, null, Modifier.size(18.dp))
            Text(if (compact) "Buy" else "  Buy", fontWeight = FontWeight.ExtraBold, fontSize = if (compact) 11.sp else 14.sp)
        }
    }
}
