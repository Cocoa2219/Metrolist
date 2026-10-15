package com.metrolist.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text

class MainActivity : ComponentActivity() {
    private val app get() = application as WearApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val account by app.account.collectAsState()
            MaterialTheme {
                AppScaffold {
                    ScreenScaffold { contentPadding ->
                        AccountStatus(account, Modifier.fillMaxSize().padding(contentPadding))
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        app.refreshAccount()
    }
}

@Composable
private fun AccountStatus(
    account: AccountState,
    modifier: Modifier = Modifier,
) {
    val (title, hint) =
        when (account) {
            AccountState.Loading -> stringResource(R.string.account_checking) to null
            AccountState.SignedOut -> stringResource(R.string.account_signed_out) to stringResource(R.string.account_signed_out_hint)
            is AccountState.SignedIn -> stringResource(R.string.account_signed_in, account.name) to null
            AccountState.Failed -> stringResource(R.string.account_failed) to stringResource(R.string.account_failed_hint)
        }
    Column(
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (hint != null) {
            Text(hint, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        }
    }
}
