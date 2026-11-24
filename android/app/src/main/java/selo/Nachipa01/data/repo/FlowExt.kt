package selo.Nachipa01.data.repo

import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Live listeners: these are what keep the app in sync with back office edits. */
fun DocumentReference.live(): Flow<DocumentSnapshot> = callbackFlow {
    val reg = addSnapshotListener { snap, err ->
        if (err != null) { close(err); return@addSnapshotListener }
        if (snap != null) trySend(snap)
    }
    awaitClose { reg.remove() }
}

fun Query.live(): Flow<QuerySnapshot> = callbackFlow {
    val reg = addSnapshotListener { snap, err ->
        if (err != null) { close(err); return@addSnapshotListener }
        if (snap != null) trySend(snap)
    }
    awaitClose { reg.remove() }
}
