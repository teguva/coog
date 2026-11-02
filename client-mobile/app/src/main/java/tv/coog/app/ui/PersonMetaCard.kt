package tv.coog.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import tv.coog.app.ui.theme.CoogTextMuted
import tv.coog.app.ui.theme.CoogType

data class PersonMetaRow(
    val label: String,
    val value: String,
)

@Composable
fun PersonMetaSideCard(
    rows: List<PersonMetaRow>,
    modifier: Modifier = Modifier,
    title: String = "DETAILS",
    width: Dp = 200.dp,
    /** When true, stretch to parent height and scroll (parent must have a bounded height). */
    fillHeight: Boolean = false,
) {
    if (rows.isEmpty()) return
    val body = Modifier
        .width(width)
        .then(
            if (fillHeight) {
                Modifier.fillMaxHeight()
            } else {
                // Bound height so this is safe inside LazyColumn (unbounded max height).
                Modifier.heightIn(max = 320.dp)
            },
        )
        .clip(RoundedCornerShape(14.dp))
        .background(Color(0xD91C1C22))
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 12.dp, vertical = 12.dp)
    Column(
        modifier = modifier.then(body),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, style = CoogType.cardYear, color = CoogTextMuted)
        rows.forEach { row ->
            PersonMetaLine(label = row.label, value = row.value)
        }
    }
}

@Composable
private fun PersonMetaLine(label: String, value: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            label.uppercase(),
            style = CoogType.cardYear.copy(fontSize = 11.sp, letterSpacing = 0.6.sp),
            color = CoogTextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            value,
            style = CoogType.cardTitle.copy(fontSize = 13.sp, lineHeight = 17.sp),
            color = Color.White.copy(alpha = 0.92f),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

fun formatPersonBirthday(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return null
    val parts = trimmed.split("-")
    if (parts.size != 3) return trimmed
    val year = parts[0]
    val month = parts[1].toIntOrNull() ?: return trimmed
    val day = parts[2].toIntOrNull() ?: return trimmed
    val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    val label = months.getOrNull(month - 1) ?: return trimmed
    return "$day $label $year"
}

fun personMetaRows(
    birthday: String = "",
    birthplace: String = "",
    department: String = "",
    ethnicity: String = "",
    nationality: String = "",
    hairColor: String = "",
    eyeColor: String = "",
    height: String = "",
    weight: String = "",
    measurements: String = "",
    shoeSize: String = "",
    tattoos: String = "",
    piercings: String = "",
    yearsActive: String = "",
    aliases: List<String> = emptyList(),
    movieCount: Int = 0,
    seriesCount: Int = 0,
    sceneCount: Int = 0,
): List<PersonMetaRow> = buildList {
    formatPersonBirthday(birthday)?.let { add(PersonMetaRow("Birthday", it)) }
    birthplace.trim().takeIf { it.isNotBlank() }?.let { add(PersonMetaRow("Born in", it)) }
    department.trim().takeIf { it.isNotBlank() }?.let { add(PersonMetaRow("Known for", it)) }
    ethnicity.trim().takeIf { it.isNotBlank() }?.let { add(PersonMetaRow("Ethnicity", it)) }
    nationality.trim().takeIf { it.isNotBlank() && !it.equals(ethnicity.trim(), ignoreCase = true) }
        ?.let { add(PersonMetaRow("Nationality", it)) }
    hairColor.trim().takeIf { it.isNotBlank() }?.let { add(PersonMetaRow("Hair", it)) }
    eyeColor.trim().takeIf { it.isNotBlank() }?.let { add(PersonMetaRow("Eyes", it)) }
    height.trim().takeIf { it.isNotBlank() }?.let { add(PersonMetaRow("Height", it)) }
    weight.trim().takeIf { it.isNotBlank() }?.let { add(PersonMetaRow("Weight", it)) }
    measurements.trim().takeIf { it.isNotBlank() }?.let { add(PersonMetaRow("Measurements", it)) }
    shoeSize.trim().takeIf { it.isNotBlank() }?.let { add(PersonMetaRow("Shoe size", it)) }
    tattoos.trim().takeIf { it.isNotBlank() }?.let { add(PersonMetaRow("Tattoos", it)) }
    piercings.trim().takeIf { it.isNotBlank() }?.let { add(PersonMetaRow("Piercings", it)) }
    yearsActive.trim().takeIf { it.isNotBlank() }?.let { add(PersonMetaRow("Active", it)) }
    aliases.map { it.trim() }.filter { it.isNotBlank() }.take(4).takeIf { it.isNotEmpty() }?.let {
        add(PersonMetaRow("Also known as", it.joinToString(", ")))
    }
    if (movieCount > 0) {
        add(PersonMetaRow("Movies", if (movieCount == 1) "1 title" else "$movieCount titles"))
    }
    if (seriesCount > 0) {
        add(PersonMetaRow("Series", if (seriesCount == 1) "1 title" else "$seriesCount titles"))
    }
    if (sceneCount > 0) {
        add(PersonMetaRow("Scenes", if (sceneCount == 1) "1 scene" else "$sceneCount scenes"))
    }
}
