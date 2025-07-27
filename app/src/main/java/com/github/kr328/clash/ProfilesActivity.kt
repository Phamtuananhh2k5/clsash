package com.github.kr328.clash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.setUUID
import com.github.kr328.clash.common.util.ticker
import com.github.kr328.clash.design.ProfilesDesign
import com.github.kr328.clash.design.dialog.AddProfileByTokenDialog
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.util.*
import java.util.concurrent.TimeUnit
import com.github.kr328.clash.design.R

class ProfilesActivity : BaseActivity<ProfilesDesign>() {
    override suspend fun main() {
        val design = ProfilesDesign(this)

        setContentDesign(design)

        // Vô hiệu hóa auto-update - đặt thời gian rất lớn (100 giờ)
        val ticker = ticker(TimeUnit.HOURS.toMillis(100))

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ActivityStart, Event.ProfileChanged -> {
                            design.fetch()
                        }
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        ProfilesDesign.Request.Create ->
                            startActivity(NewProfileActivity::class.intent)
                        ProfilesDesign.Request.CreateByToken ->
                            showAddProfileByTokenDialog()
                        ProfilesDesign.Request.UpdateAll ->
                            withProfile {
                                try {
                                    queryAll().forEach { p ->
                                        if (p.imported && p.type != Profile.Type.File) {
                                            update(p.uuid)
                                            // VÔ HIỆU HÓA auto-commit
                                            /*
                                            launch {
                                                try {
                                                    commit(p.uuid)
                                                } catch (e: Exception) {
                                                    println("Auto-commit failed for ${p.name}: ${e.message}")
                                                }
                                            }
                                            */
                                        }
                                    }
                                }
                                finally {
                                    withContext(Dispatchers.Main) {
                                        design.finishUpdateAll();
                                    }
                                }
                            }
                        is ProfilesDesign.Request.Update -> {
                            withProfile { 
                                update(it.profile.uuid)
                                // Tự động commit sau khi update thành công
                                launch {
                                    try {
                                        commit(it.profile.uuid)
                                    } catch (e: Exception) {
                                        // Silent handling
                                        println("Auto-commit after update failed: ${e.message}")
                                    }
                                }
                            }
                        }
                        is ProfilesDesign.Request.Delete ->
                            withProfile { delete(it.profile.uuid) }
                        is ProfilesDesign.Request.Edit ->
                            startActivity(PropertiesActivity::class.intent.setUUID(it.profile.uuid))
                        is ProfilesDesign.Request.Active -> {
                            withProfile {
                                if (it.profile.imported) {
                                    setActive(it.profile)
                                } else {
                                    // Tự động lưu và kích hoạt profile chưa được lưu
                                    try {
                                        commit(it.profile.uuid)
                                        val updatedProfile = queryByUUID(it.profile.uuid)
                                        if (updatedProfile != null && updatedProfile.imported) {
                                            setActive(updatedProfile)
                                            design?.showToast(
                                                "Profile automatically saved and activated!",
                                                com.github.kr328.clash.design.ui.ToastDuration.Long
                                            )
                                        }
                                    } catch (e: Exception) {
                                        design?.showToast(
                                            "Failed to save profile: ${e.message}",
                                            com.github.kr328.clash.design.ui.ToastDuration.Long
                                        )
                                    }
                                }
                            }
                        }
                        is ProfilesDesign.Request.Duplicate -> {
                            val uuid = withProfile { clone(it.profile.uuid) }

                            startActivity(PropertiesActivity::class.intent.setUUID(uuid))
                        }
                    }
                }
                if (activityStarted) {
                    ticker.onReceive {
                        design.updateElapsed()
                    }
                }
            }
        }
    }

    private suspend fun ProfilesDesign.fetch() {
        withProfile {
            val profiles = queryAll()
            
            // Chỉ hiển thị danh sách profiles - VÔ HIỆU HÓA auto-commit
            patchProfiles(profiles)
            
            // COMMENTED OUT: Tự động commit đã bị vô hiệu hóa
            /*
            var hasChanges = false
            profiles.forEach { profile ->
                if (!profile.imported && profile.type == Profile.Type.Url) {
                    launch {
                        try {
                            commit(profile.uuid)
                            hasChanges = true
                        } catch (e: Exception) {
                            println("Auto-commit failed for ${profile.name}: ${e.message}")
                        }
                    }
                }
            }
            
            if (hasChanges) {
                launch {
                    kotlinx.coroutines.delay(100)
                    withContext(Dispatchers.Main) {
                        patchProfiles(queryAll())
                    }
                }
            }
            */
        }
    }

    private suspend fun forceRefreshUI() {
        withContext(Dispatchers.Main) {
            design?.fetch()
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

    override fun onProfileUpdateCompleted(uuid: UUID?) {
        if(uuid == null)
            return;
        launch {
            var name: String? = null;
            withProfile {
                name = queryByUUID(uuid)?.name
            }
            design?.showToast(
                getString(R.string.toast_profile_updated_complete, name),
                ToastDuration.Long
            )
        }
    }
    override fun onProfileUpdateFailed(uuid: UUID?, reason: String?) {
        if(uuid == null)
            return;
        launch {
            var name: String? = null;
            withProfile {
                name = queryByUUID(uuid)?.name
            }
            design?.showToast(
                getString(R.string.toast_profile_updated_failed, name, reason),
                ToastDuration.Long
            ){
                setAction(R.string.edit) {
                    startActivity(PropertiesActivity::class.intent.setUUID(uuid))
                }
            }
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
                        
                        // Refresh UI ngay sau khi tạo profile
                        forceRefreshUI()
                        
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
                        
                        // Force refresh UI để đảm bảo hiển thị trạng thái mới nhất
                        forceRefreshUI()
                        
                        // Thêm một refresh cuối cùng sau delay ngắn
                        kotlinx.coroutines.delay(100)
                        forceRefreshUI()
                        
                        design?.showToast(
                            "Profile '$profileName' created with 100min auto-update, saved and activated!",
                            com.github.kr328.clash.design.ui.ToastDuration.Long
                        )
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