package com.example.fortismobile.encrypt

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PasswordGeneratorScreen() {
    var length by remember { mutableStateOf(16f) }
    var useUppercase by remember { mutableStateOf(true) }
    var useNumbers by remember { mutableStateOf(true) }
    var useSymbols by remember { mutableStateOf(true) }
    var generatedPassword by remember { mutableStateOf("xkE9!mPq2#Lz7w$@") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .padding(16.dp)
    ) {
        Text(
            text = "Generate Password",
            color = Color.White,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF1E1E1E))
                .padding(16.dp)
                .clickable { /* Copy to clipboard */ },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = generatedPassword,
                color = Color.White,
                fontSize = 24.sp,
                fontFamily = FontFamily.Monospace
            )
            Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = Color.Gray)
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(text = "Length: ${length.toInt()}", color = Color.White, fontSize = 16.sp)
        Slider(
            value = length,
            onValueChange = { length = it },
            valueRange = 8f..64f,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color(0xFF4CAF50)
            )
        )

        Spacer(modifier = Modifier.height(16.dp))

        OptionToggle("Uppercase (A-Z)", useUppercase) { useUppercase = it }
        OptionToggle("Numbers (0-9)", useNumbers) { useNumbers = it }
        OptionToggle("Symbols (!@#$)", useSymbols) { useSymbols = it }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = { /* Generate password via fortis_bindings */ },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("GENERATE", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun OptionToggle(label: String, isChecked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color.White, fontSize = 18.sp)
        Switch(
            checked = isChecked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF4CAF50))
        )
    }
}
