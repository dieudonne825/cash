package com.pettycash.cashsms.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.pettycash.cashsms.R

@Composable
fun AppLogo(
    modifier: Modifier = Modifier,
    size: Int = 36,
    iconSize: Int = 20
) {
    Image(
        painter = painterResource(id = R.drawable.img_app_logo),
        contentDescription = "Cash SMS Logo",
        contentScale = ContentScale.Crop,
        modifier = modifier
            .size(size.dp)
            .clip(CircleShape)
    )
}
