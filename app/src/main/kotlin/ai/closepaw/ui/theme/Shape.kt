package ai.closepaw.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// D1 §4.3: three named radii. small=controls/fields, medium=cards/user bubble, large=capsule/pill chrome.
val ClosePawShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(16.dp),
)
