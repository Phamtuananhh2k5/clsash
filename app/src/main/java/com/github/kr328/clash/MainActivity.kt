package com.github.kr328.clash

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.ticker
import com.github.kr328.clash.design.MainDesign
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.dialog.AddProfileByTokenDialog
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.stopClashService
import com.github.kr328.clash.util.withClash
import com.github.kr328.clash.util.withProfile
import com.github.kr328.clash.core.bridge.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.util.*
import java.util.concurrent.TimeUnit
import com.github.kr328.clash.design.R

class MainActivity : BaseActivity<MainDesign>() {
    override suspend fun main() {
        val design = MainDesign(this)

        setContentDesign(design)

        design.fetch()

        val ticker = ticker(TimeUnit.SECONDS.toMillis(1))

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ActivityStart,
                        Event.ServiceRecreated,
                        Event.ClashStop, Event.ClashStart,
                        Event.ProfileLoaded, Event.ProfileChanged -> design.fetch()
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        MainDesign.Request.ToggleStatus -> {
                            if (clashRunning)
                                stopClashService()
                            else
                                design.startClash()
                        }
                        MainDesign.Request.OpenProxy ->
                            startActivity(ProxyActivity::class.intent)
                        MainDesign.Request.OpenProfiles ->
                            openProfilesOrTokenDialog()
                        MainDesign.Request.OpenProviders ->
                            startActivity(ProvidersActivity::class.intent)
                        MainDesign.Request.OpenLogs -> {
                            if (LogcatService.running) {
                                startActivity(LogcatActivity::class.intent)
                            } else {
                                startActivity(LogsActivity::class.intent)
                            }
                        }
                        MainDesign.Request.OpenSettings ->
                            startActivity(SettingsActivity::class.intent)
                    }
                }
                if (clashRunning) {
                    ticker.onReceive {
                        design.fetchTraffic()
                    }
                }
            }
        }
    }

    private suspend fun MainDesign.fetch() {
        setClashRunning(clashRunning)

        val state = withClash {
            queryTunnelState()
        }
        val providers = withClash {
            queryProviders()
        }

        setMode(state.mode)
        setHasProviders(providers.isNotEmpty())

        withProfile {
            setProfileName(queryActive()?.name)
        }
    }

    private suspend fun MainDesign.fetchTraffic() {
        withClash {
            setForwarded(queryTrafficTotal())
        }
    }

    private suspend fun MainDesign.startClash() {
        val active = withProfile { queryActive() }

        if (active == null || !active.imported) {
            showToast(R.string.no_profile_selected, ToastDuration.Long) {
                setAction(R.string.profiles) {
                    startActivity(ProfilesActivity::class.intent)
                }
            }

            return
        }

        val vpnRequest = startClashService()

        try {
            if (vpnRequest != null) {
                val result = startActivityForResult(
                    ActivityResultContracts.StartActivityForResult(),
                    vpnRequest
                )

                if (result.resultCode == RESULT_OK)
                    startClashService()
            }
        } catch (e: Exception) {
            design?.showToast(R.string.unable_to_start_vpn, ToastDuration.Long)
        }
    }

    private suspend fun queryAppVersionName(): String {
        return withContext(Dispatchers.IO) {
            packageManager.getPackageInfo(packageName, 0).versionName + "\n" + Bridge.nativeCoreVersion().replace("_", "-")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val requestPermissionLauncher =
                registerForActivityResult(RequestPermission()
                ) { isGranted: Boolean ->
                }
            if (ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
    
    private suspend fun openProfilesOrTokenDialog() {
        // Kiểm tra xem có profile nào không
        val profileCount = withProfile {
            queryAll().size
        }
        
        if (profileCount == 0) {
            // Không có profile nào, tự động mở dialog nhập token
            showAddProfileByTokenDialog()
        } else {
            // Có profile, mở ProfilesActivity bình thường
            startActivity(ProfilesActivity::class.intent)
        }
    }
    
    private suspend fun generateNextProfileName(): String {
        return withProfile {
            val profiles = queryAll()
            
            // Tìm tất cả profile có tên bắt đầu với "DuaLeoVPN"
            val duaLeoProfiles = profiles.filter { it.name.startsWith("DuaLeoVPN") }
            
            // Nếu chưa có profile nào, bắt đầu từ 1
            if (duaLeoProfiles.isEmpty()) {
                return@withProfile "DuaLeoVPN 1"
            }
            
            // Tìm số lớn nhất trong các profile hiện có
            var maxNumber = 0
            duaLeoProfiles.forEach { profile ->
                val name = profile.name
                // Tách số từ tên profile (ví dụ: "DuaLeoVPN 3" -> 3)
                val regex = """DuaLeoVPN\s+(\d+)""".toRegex()
                val matchResult = regex.find(name)
                if (matchResult != null) {
                    val number = matchResult.groupValues[1].toIntOrNull()
                    if (number != null && number > maxNumber) {
                        maxNumber = number
                    }
                }
            }
            
            // Trả về tên với số tiếp theo
            return@withProfile "DuaLeoVPN ${maxNumber + 1}"
        }
    }

    private suspend fun forceRefreshUI() {
        withContext(Dispatchers.Main) {
            design?.fetch()
        }
    }

    private fun showAddProfileByTokenDialog() {
        val dialog = AddProfileByTokenDialog(
            context = this,
            onSuccess = { url, token ->
                launch {
                    try {
                        // Generate tên profile DuaLeoVPN tự động
                        val profileName = generateNextProfileName()
                        
                        val uuid = withProfile {
                            create(
                                type = Profile.Type.Url,
                                name = profileName,
                                source = url
                            )
                        }
                        
                        // Set thời gian tự động cập nhật 100 phút
                        withProfile {
                            patch(
                                uuid = uuid,
                                name = profileName,
                                source = url,
                                interval = TimeUnit.MINUTES.toMillis(100) // 100 phút
                            )
                        }
                        
                        // Workflow tự động hoàn chỉnh
                        withProfile {
                            // 1. Update để import dữ liệu
                            update(uuid)
                            // 2. Commit để sẵn sàng sử dụng
                            commit(uuid)
                            // 3. Tự động kích hoạt profile mới
                            val profile = queryByUUID(uuid)
                            if (profile != null && profile.imported) {
                                setActive(profile)
                            }
                        }
                        
                        // Refresh UI để hiển thị trạng thái mới
                        forceRefreshUI()
                        
                        design?.showToast(
                            "Profile '$profileName' created with 100min auto-update, saved and activated! Now you can go to Profiles to manage.",
                            com.github.kr328.clash.design.ui.ToastDuration.Long
                        ) {
                            setAction("Profiles") {
                                startActivity(ProfilesActivity::class.intent)
                            }
                        }
                    } catch (e: Exception) {
                        // Đảm bảo UI được refresh ngay cả khi có lỗi
                        forceRefreshUI()
                        design?.showToast(
                            "Failed to create profile: ${e.message}",
                            com.github.kr328.clash.design.ui.ToastDuration.Long
                        )
                    }
                }
            },
            onError = { error ->
                launch {
                    design?.showToast(error, com.github.kr328.clash.design.ui.ToastDuration.Long)
                }
            }
        )
        dialog.show()
    }
}

val mainActivityAlias = "${MainActivity::class.java.name}Alias"