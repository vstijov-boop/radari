package cg.radari.app.auto

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * Ulazna tacka za Android Auto (kategorija NAVIGATION).
 *
 * App se ne objavljuje na Play Store nego se sideload-uje, pa se hostovi ne mogu
 * provjeravati preko potpisa iz `res/xml/hosts_allowlist` — koristi se dozvoljavanje
 * svih hostova, sto za app koju instalira sama ekipa nije rizik.
 */
class RadariCarService : CarAppService() {

    override fun createHostValidator(): HostValidator =
        HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = RadariSession()
}
