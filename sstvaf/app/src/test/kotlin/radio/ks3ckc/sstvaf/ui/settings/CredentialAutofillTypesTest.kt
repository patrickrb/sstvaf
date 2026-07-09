package radio.ks3ckc.sstvaf.ui.settings

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.autofill.AutofillType
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import radio.ks3ckc.sstvaf.ui.components.CredentialFieldRole
import radio.ks3ckc.sstvaf.ui.components.credentialAutofillTypes

/**
 * Covers the field-role → autofill-hint mapping used by the ICOM login dialog
 * (issue #36). The `Modifier.autofill` glue can't be unit-tested, so the decision is
 * extracted into `credentialAutofillTypes`, which is what's tested here.
 */
@OptIn(ExperimentalComposeUiApi::class)
class CredentialAutofillTypesTest {

    @Test
    fun username_role_advertises_username_only() {
        assertThat(credentialAutofillTypes(CredentialFieldRole.USERNAME))
            .containsExactly(AutofillType.Username)
    }

    @Test
    fun email_role_advertises_email_address_only() {
        assertThat(credentialAutofillTypes(CredentialFieldRole.EMAIL))
            .containsExactly(AutofillType.EmailAddress)
    }

    @Test
    fun password_role_advertises_password_only() {
        assertThat(credentialAutofillTypes(CredentialFieldRole.PASSWORD))
            .containsExactly(AutofillType.Password)
    }

    @Test
    fun username_role_never_advertises_email_address() {
        // ICOM usernames are not emails — mislabeling would let a manager fill an
        // email address into the username field.
        assertThat(credentialAutofillTypes(CredentialFieldRole.USERNAME))
            .doesNotContain(AutofillType.EmailAddress)
    }

    @Test
    fun every_role_maps_to_exactly_one_hint() {
        // Each credential field advertises a single, unambiguous type so the autofill
        // service classifies it deterministically.
        for (role in CredentialFieldRole.entries) {
            assertThat(credentialAutofillTypes(role)).hasSize(1)
        }
    }
}
