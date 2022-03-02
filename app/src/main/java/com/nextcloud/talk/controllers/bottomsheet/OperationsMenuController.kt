/*
 * Nextcloud Talk application
 *
 * @author Mario Danic
 * Copyright (C) 2017 Mario Danic <mario@lovelyhq.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.nextcloud.talk.controllers.bottomsheet

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.text.TextUtils
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import autodagger.AutoInjector
import com.bluelinelabs.conductor.RouterTransaction
import com.bluelinelabs.conductor.changehandler.HorizontalChangeHandler
import com.bluelinelabs.logansquare.LoganSquare
import com.nextcloud.talk.R
import com.nextcloud.talk.api.NcApi
import com.nextcloud.talk.application.NextcloudTalkApplication
import com.nextcloud.talk.application.NextcloudTalkApplication.Companion.sharedApplication
import com.nextcloud.talk.controllers.base.NewBaseController
import com.nextcloud.talk.controllers.util.viewBinding
import com.nextcloud.talk.databinding.ControllerOperationsMenuBinding
import com.nextcloud.talk.events.ConversationsListFetchDataEvent
import com.nextcloud.talk.events.OpenConversationEvent
import com.nextcloud.talk.models.RetrofitBucket
import com.nextcloud.talk.models.database.CapabilitiesUtil
import com.nextcloud.talk.models.database.UserEntity
import com.nextcloud.talk.models.json.capabilities.Capabilities
import com.nextcloud.talk.models.json.capabilities.CapabilitiesOverall
import com.nextcloud.talk.models.json.conversations.Conversation
import com.nextcloud.talk.models.json.conversations.RoomOverall
import com.nextcloud.talk.models.json.generic.GenericOverall
import com.nextcloud.talk.models.json.participants.AddParticipantOverall
import com.nextcloud.talk.utils.ApiUtils
import com.nextcloud.talk.utils.DisplayUtils
import com.nextcloud.talk.utils.NoSupportedApiException
import com.nextcloud.talk.utils.bundle.BundleKeys
import com.nextcloud.talk.utils.database.user.UserUtils
import com.nextcloud.talk.utils.singletons.ApplicationWideMessageHolder
import io.reactivex.Observer
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.internal.synchronized
import org.greenrobot.eventbus.EventBus
import org.parceler.Parcels
import retrofit2.HttpException
import java.io.IOException
import java.util.ArrayList
import javax.inject.Inject

@AutoInjector(NextcloudTalkApplication::class)
class OperationsMenuController(args: Bundle) :
    NewBaseController(
        R.layout.controller_operations_menu,
        args
    ) {
    private val binding: ControllerOperationsMenuBinding by viewBinding(ControllerOperationsMenuBinding::bind)

    @JvmField
    @Inject
    var ncApi: NcApi? = null

    @JvmField
    @Inject
    var userUtils: UserUtils? = null

    @JvmField
    @Inject
    var eventBus: EventBus? = null

    private val operation: ConversationOperationEnum
    private var conversation: Conversation? = null
    private var currentUser: UserEntity? = null
    private val callPassword: String
    private val callUrl: String
    private var baseUrl: String? = null
    private var conversationToken: String? = null
    private var disposable: Disposable? = null
    private var conversationType: Conversation.ConversationType? = null
    private var invitedUsers = ArrayList<String>()
    private var invitedGroups = ArrayList<String>()
    private var serverCapabilities: Capabilities? = null
    private var credentials: String? = null
    private val conversationName: String

    override val appBarLayoutType: AppBarLayoutType
        get() = AppBarLayoutType.SEARCH_BAR

    override fun onViewBound(view: View) {
        super.onViewBound(view)

        currentUser = userUtils!!.currentUser
        if (!TextUtils.isEmpty(callUrl) && callUrl.contains("/call")) {
            conversationToken = callUrl.substring(callUrl.lastIndexOf("/") + 1)
            if (callUrl.contains("/index.php")) {
                baseUrl = callUrl.substring(0, callUrl.indexOf("/index.php"))
            } else {
                baseUrl = callUrl.substring(0, callUrl.indexOf("/call"))
            }
        }

        if (!TextUtils.isEmpty(baseUrl) && baseUrl != currentUser!!.baseUrl) {
            if (serverCapabilities != null) {
                try {
                    useBundledCapabilitiesForGuest()
                } catch (e: IOException) {
                    // Fall back to fetching capabilities again
                    fetchCapabilitiesForGuest()
                }
            } else {
                fetchCapabilitiesForGuest()
            }
        } else {
            processOperation()
        }
    }

    @SuppressLint("LongLogTag")
    private fun useBundledCapabilitiesForGuest() {
        currentUser = UserEntity()
        currentUser!!.baseUrl = baseUrl
        currentUser!!.userId = "?"
        try {
            currentUser!!.capabilities = LoganSquare.serialize<Capabilities>(serverCapabilities)
        } catch (e: IOException) {
            Log.e("OperationsMenu", "Failed to serialize capabilities")
            throw e
        }
        try {
            checkCapabilities(currentUser)
            processOperation()
        } catch (e: NoSupportedApiException) {
            showResultImage(false, false)
            Log.d(TAG, "No supported server version found", e)
        }
    }

    @SuppressLint("LongLogTag")
    private fun fetchCapabilitiesForGuest() {
        ncApi!!.getCapabilities(null, ApiUtils.getUrlForCapabilities(baseUrl))
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(object : Observer<CapabilitiesOverall> {
                override fun onSubscribe(d: Disposable) {}

                @SuppressLint("LongLogTag")
                override fun onNext(capabilitiesOverall: CapabilitiesOverall) {
                    currentUser = UserEntity()
                    currentUser!!.baseUrl = baseUrl
                    currentUser!!.userId = "?"
                    try {
                        currentUser!!.capabilities = LoganSquare
                            .serialize<Capabilities>(
                                capabilitiesOverall
                                    .ocs!!
                                    .data!!
                                    .capabilities
                            )
                    } catch (e: IOException) {
                        Log.e("OperationsMenu", "Failed to serialize capabilities")
                    }
                    try {
                        checkCapabilities(currentUser)
                        processOperation()
                    } catch (e: NoSupportedApiException) {
                        showResultImage(false, false)
                        Log.d(TAG, "No supported server version found", e)
                    }
                }

                @SuppressLint("LongLogTag")
                override fun onError(e: Throwable) {
                    showResultImage(false, false)
                    Log.e(TAG, "Error fetching capabilities for guest", e)
                }

                override fun onComplete() {}
            })
    }

    @SuppressLint("LongLogTag")
    private fun processOperation() {
        val roomOperationsObserver = RoomOperationsObserver()
        val genericOperationsObserver = GenericOperationsObserver()
        if (currentUser == null) {
            showResultImage(false, true)
            Log.e(TAG, "Ended up in processOperation without a valid currentUser")
            return
        }
        credentials = ApiUtils.getCredentials(currentUser!!.username, currentUser!!.token)
        val apiVersion: Int =
            ApiUtils.getConversationApiVersion(currentUser, intArrayOf(ApiUtils.APIv4, ApiUtils.APIv1))
        val chatApiVersion: Int = ApiUtils.getChatApiVersion(currentUser, intArrayOf(ApiUtils.APIv1))
        when (operation) {
            ConversationOperationEnum.OPS_CODE_RENAME_ROOM -> ncApi!!.renameRoom(
                credentials, ApiUtils.getUrlForRoom(
                    apiVersion, currentUser!!.baseUrl,
                    conversation!!.getToken()
                ),
                conversation!!.getName()
            )
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .retry(1)
                .subscribe(genericOperationsObserver)
            ConversationOperationEnum.OPS_CODE_MAKE_PUBLIC -> ncApi!!.makeRoomPublic(
                credentials, ApiUtils.getUrlForRoomPublic(
                    apiVersion, currentUser!!.baseUrl,
                    conversation!!.getToken()
                )
            )
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .retry(1)
                .subscribe(genericOperationsObserver)
            ConversationOperationEnum.OPS_CODE_CHANGE_PASSWORD,
            ConversationOperationEnum.OPS_CODE_CLEAR_PASSWORD,
            ConversationOperationEnum.OPS_CODE_SET_PASSWORD -> {
                var pass = ""
                if (conversation!!.getPassword() != null) {
                    pass = conversation!!.getPassword()
                }
                ncApi!!.setPassword(
                    credentials, ApiUtils.getUrlForRoomPassword(
                        apiVersion, currentUser!!.baseUrl,
                        conversation!!.getToken()
                    ), pass
                )
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .retry(1)
                    .subscribe(genericOperationsObserver)
            }
            ConversationOperationEnum.OPS_CODE_MAKE_PRIVATE -> ncApi!!.makeRoomPrivate(
                credentials, ApiUtils.getUrlForRoomPublic(
                    apiVersion,
                    currentUser!!.baseUrl,
                    conversation!!.getToken()
                )
            )
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .retry(1)
                .subscribe(genericOperationsObserver)
            ConversationOperationEnum.OPS_CODE_GET_AND_JOIN_ROOM -> ncApi!!.getRoom(
                credentials,
                ApiUtils.getUrlForRoom(apiVersion, baseUrl, conversationToken)
            )
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .retry(1)
                .subscribe(object : Observer<RoomOverall> {
                    override fun onSubscribe(d: Disposable) {
                        disposable = d
                    }

                    override fun onNext(roomOverall: RoomOverall) {
                        conversation = roomOverall.getOcs().getData()
                        if (conversation!!.isHasPassword && conversation!!.isGuest) {
                            eventBus!!.post(ConversationsListFetchDataEvent())
                            val bundle = Bundle()
                            bundle.putParcelable(BundleKeys.KEY_ROOM, Parcels.wrap<Conversation>(conversation))
                            bundle.putString(BundleKeys.KEY_CALL_URL, callUrl)
                            try {
                                bundle.putParcelable(
                                    BundleKeys.KEY_SERVER_CAPABILITIES,
                                    Parcels.wrap(
                                        LoganSquare.parse(
                                            currentUser!!.capabilities,
                                            Capabilities::class.java
                                        )
                                    )
                                )
                            } catch (e: IOException) {
                                Log.e(TAG, "Failed to parse capabilities for guest")
                                showResultImage(false, false)
                            }
                            bundle.putSerializable(
                                BundleKeys.KEY_OPERATION_CODE,
                                ConversationOperationEnum.OPS_CODE_JOIN_ROOM
                            )
                            getRouter().pushController(
                                RouterTransaction.with(EntryMenuController(bundle))
                                    .pushChangeHandler(HorizontalChangeHandler())
                                    .popChangeHandler(HorizontalChangeHandler())
                            )
                        } else if (conversation!!.isGuest) {
                            ncApi!!.joinRoom(
                                credentials, ApiUtils.getUrlForParticipantsActive(
                                    apiVersion,
                                    baseUrl,
                                    conversationToken
                                ), null
                            )
                                .subscribeOn(Schedulers.io())
                                .observeOn(AndroidSchedulers.mainThread())
                                .subscribe(object : Observer<RoomOverall> {
                                    override fun onSubscribe(
                                        d: Disposable
                                    ) {
                                    }

                                    override fun onNext(
                                        roomOverall: RoomOverall
                                    ) {
                                        conversation = roomOverall.getOcs().getData()
                                        initiateConversation(false)
                                    }

                                    override fun onError(e: Throwable) {
                                        showResultImage(false, false)
                                        dispose()
                                    }

                                    override fun onComplete() {}
                                })
                        } else {
                            initiateConversation(false)
                        }
                    }

                    override fun onError(e: Throwable) {
                        showResultImage(false, false)
                        dispose()
                    }

                    override fun onComplete() {
                        dispose()
                    }
                })
            ConversationOperationEnum.OPS_CODE_INVITE_USERS -> {
                val retrofitBucket: RetrofitBucket
                var invite: String? = null
                if (invitedGroups.size > 0) {
                    invite = invitedGroups[0]
                }
                retrofitBucket = if (conversationType == Conversation.ConversationType.ROOM_PUBLIC_CALL) {
                    ApiUtils.getRetrofitBucketForCreateRoom(
                        apiVersion, currentUser!!.baseUrl,
                        "3", null, invite, conversationName
                    )
                } else {
                    ApiUtils.getRetrofitBucketForCreateRoom(
                        apiVersion, currentUser!!.baseUrl,
                        "2", null, invite, conversationName
                    )
                }
                ncApi!!.createRoom(credentials, retrofitBucket.getUrl(), retrofitBucket.getQueryMap())
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .retry(1)
                    .subscribe(object : Observer<RoomOverall> {
                        override fun onSubscribe(d: Disposable) {}
                        override fun onNext(roomOverall: RoomOverall) {
                            conversation = roomOverall.getOcs().getData()
                            ncApi!!.getRoom(
                                credentials,
                                ApiUtils.getUrlForRoom(
                                    apiVersion, currentUser!!.baseUrl,
                                    conversation!!.getToken()
                                )
                            )
                                .subscribeOn(Schedulers.io())
                                .observeOn(AndroidSchedulers.mainThread())
                                .subscribe(object : Observer<RoomOverall> {
                                    override fun onSubscribe(d: Disposable) {}
                                    override fun onNext(
                                        roomOverall: RoomOverall
                                    ) {
                                        conversation = roomOverall.getOcs().getData()
                                        inviteUsersToAConversation()
                                    }

                                    override fun onError(e: Throwable) {
                                        showResultImage(false, false)
                                        dispose()
                                    }

                                    override fun onComplete() {}
                                })
                        }

                        override fun onError(e: Throwable) {
                            showResultImage(false, false)
                            dispose()
                        }

                        override fun onComplete() {
                            dispose()
                        }
                    })
            }
            ConversationOperationEnum.OPS_CODE_MARK_AS_READ -> ncApi!!.setChatReadMarker(
                credentials,
                ApiUtils.getUrlForSetChatReadMarker(
                    chatApiVersion,
                    currentUser!!.baseUrl,
                    conversation!!.getToken()
                ),
                conversation!!.lastMessage.jsonMessageId
            )
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .retry(1)
                .subscribe(genericOperationsObserver)
            ConversationOperationEnum.OPS_CODE_REMOVE_FAVORITE,
            ConversationOperationEnum.OPS_CODE_ADD_FAVORITE ->
                if (operation === ConversationOperationEnum.OPS_CODE_REMOVE_FAVORITE) {
                    ncApi!!.removeConversationFromFavorites(
                        credentials,
                        ApiUtils.getUrlForRoomFavorite(
                            apiVersion,
                            currentUser!!.baseUrl,
                            conversation!!.getToken()
                        )
                    )
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .retry(1)
                        .subscribe(genericOperationsObserver)
                } else {
                    ncApi!!.addConversationToFavorites(
                        credentials,
                        ApiUtils.getUrlForRoomFavorite(
                            apiVersion,
                            currentUser!!.baseUrl,
                            conversation!!.getToken()
                        )
                    )
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .retry(1)
                        .subscribe(genericOperationsObserver)
                }
            ConversationOperationEnum.OPS_CODE_JOIN_ROOM -> ncApi!!.joinRoom(
                credentials, ApiUtils.getUrlForParticipantsActive(
                    apiVersion,
                    baseUrl,
                    conversationToken
                ),
                callPassword
            )
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .retry(1)
                .subscribe(roomOperationsObserver)
            else -> {
            }
        }
    }

    private fun showResultImage(everythingOK: Boolean, isGuestSupportError: Boolean) {
        binding.progressBar.visibility = View.GONE
        if (resources != null) {
            if (everythingOK) {
                binding.resultImageView.setImageDrawable(
                    DisplayUtils.getTintedDrawable(
                        resources,
                        R.drawable.ic_check_circle_black_24dp,
                        R.color.nc_darkGreen
                    )
                )
            } else {
                binding.resultImageView.setImageDrawable(
                    DisplayUtils.getTintedDrawable(
                        resources,
                        R.drawable.ic_cancel_black_24dp,
                        R.color.nc_darkRed
                    )
                )
            }
        }
        binding.resultImageView.visibility = View.VISIBLE
        if (everythingOK) {
            binding.resultTextView.setText(R.string.nc_all_ok_operation)
        } else {
            binding.resultTextView.setTextColor(resources!!.getColor(R.color.nc_darkRed))
            if (!isGuestSupportError) {
                binding.resultTextView.setText(R.string.nc_failed_to_perform_operation)
            } else {
                binding.resultTextView.setText(R.string.nc_failed_signaling_settings)
                binding.webButton.setOnClickListener { v: View? ->
                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(callUrl))
                    startActivity(browserIntent)
                }
                binding.webButton.visibility = View.VISIBLE
            }
        }
        binding.resultTextView.setVisibility(View.VISIBLE)
        if (everythingOK) {
            eventBus!!.post(ConversationsListFetchDataEvent())
        } else {
            binding.resultImageView.setImageDrawable(
                DisplayUtils.getTintedDrawable(
                    resources,
                    R.drawable.ic_cancel_black_24dp,
                    R.color.nc_darkRed
                )
            )
            binding.okButton.setOnClickListener { v: View? -> eventBus!!.post(ConversationsListFetchDataEvent()) }
            binding.okButton.visibility = View.VISIBLE
        }
    }

    private fun dispose() {
        if (disposable != null && !disposable!!.isDisposed) {
            disposable!!.dispose()
        }
        disposable = null
    }

    override fun onDestroy() {
        super.onDestroy()
        dispose()
    }

    @kotlin.Throws(NoSupportedApiException::class)
    private fun checkCapabilities(currentUser: UserEntity?) {
        ApiUtils.getConversationApiVersion(currentUser, intArrayOf(ApiUtils.APIv4, 1))
        ApiUtils.getCallApiVersion(currentUser, intArrayOf(ApiUtils.APIv4, 1))
        ApiUtils.getChatApiVersion(currentUser, intArrayOf(1))
        ApiUtils.getSignalingApiVersion(currentUser, intArrayOf(ApiUtils.APIv3, 2, 1))
    }

    private fun inviteUsersToAConversation() {
        val localInvitedUsers = invitedUsers
        val localInvitedGroups = invitedGroups
        if (localInvitedGroups.size > 0) {
            localInvitedGroups.removeAt(0)
        }
        val apiVersion: Int = ApiUtils.getConversationApiVersion(currentUser, intArrayOf(4, 1))
        if (localInvitedUsers.size > 0 || localInvitedGroups.size > 0 &&
            CapabilitiesUtil.hasSpreedFeatureCapability(currentUser, "invite-groups-and-mails")
        ) {
            addGroupsToConversation(localInvitedUsers, localInvitedGroups, apiVersion)
            addUsersToConversation(localInvitedUsers, localInvitedGroups, apiVersion)
        } else {
            initiateConversation(true)
        }
    }

    private fun addUsersToConversation(
        localInvitedUsers: ArrayList<String>,
        localInvitedGroups: ArrayList<String>,
        apiVersion: Int
    ) {
        var retrofitBucket: RetrofitBucket
        for (i in localInvitedUsers.indices) {
            val userId = invitedUsers[i]
            retrofitBucket = ApiUtils.getRetrofitBucketForAddParticipant(
                apiVersion,
                currentUser!!.baseUrl,
                conversation!!.getToken(),
                userId
            )
            ncApi!!.addParticipant(credentials, retrofitBucket.getUrl(), retrofitBucket.getQueryMap())
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .retry(1)
                .subscribe(object : Observer<AddParticipantOverall> {
                    override fun onSubscribe(d: Disposable) {}
                    override fun onNext(
                        addParticipantOverall: AddParticipantOverall
                    ) {
                    }

                    override fun onError(e: Throwable) {
                        dispose()
                    }

                    override fun onComplete() {
                        synchronized(localInvitedUsers) { localInvitedUsers.remove(userId) }
                        if (localInvitedGroups.size == 0 && localInvitedUsers.size == 0) {
                            initiateConversation(true)
                        }
                        dispose()
                    }
                })
        }
    }

    private fun addGroupsToConversation(
        localInvitedUsers: ArrayList<String>,
        localInvitedGroups: ArrayList<String>,
        apiVersion: Int
    ) {
        var retrofitBucket: RetrofitBucket
        if (localInvitedGroups.size > 0 &&
            CapabilitiesUtil.hasSpreedFeatureCapability(currentUser, "invite-groups-and-mails")
        ) {
            for (i in localInvitedGroups.indices) {
                val groupId = localInvitedGroups[i]
                retrofitBucket = ApiUtils.getRetrofitBucketForAddParticipantWithSource(
                    apiVersion,
                    currentUser!!.baseUrl,
                    conversation!!.getToken(),
                    "groups",
                    groupId
                )
                ncApi!!.addParticipant(credentials, retrofitBucket.getUrl(), retrofitBucket.getQueryMap())
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .retry(1)
                    .subscribe(object : Observer<AddParticipantOverall> {
                        override fun onSubscribe(d: Disposable) {}
                        override fun onNext(
                            addParticipantOverall: AddParticipantOverall
                        ) {
                        }

                        override fun onError(e: Throwable) {
                            dispose()
                        }

                        override fun onComplete() {
                            synchronized(localInvitedGroups) { localInvitedGroups.remove(groupId) }
                            if (localInvitedGroups.size == 0 && localInvitedUsers.size == 0) {
                                initiateConversation(true)
                            }
                            dispose()
                        }
                    })
            }
        }
    }

    private fun initiateConversation(dismissView: Boolean) {
        eventBus!!.post(ConversationsListFetchDataEvent())
        val bundle = Bundle()
        bundle.putString(BundleKeys.KEY_ROOM_TOKEN, conversation!!.getToken())
        bundle.putString(BundleKeys.KEY_ROOM_ID, conversation!!.getRoomId())
        bundle.putString(BundleKeys.KEY_CONVERSATION_NAME, conversation!!.getDisplayName())
        bundle.putParcelable(BundleKeys.KEY_USER_ENTITY, currentUser)
        bundle.putParcelable(BundleKeys.KEY_ACTIVE_CONVERSATION, Parcels.wrap<Conversation>(conversation))
        bundle.putString(BundleKeys.KEY_CONVERSATION_PASSWORD, callPassword)
        eventBus!!.post(OpenConversationEvent(conversation, bundle))
    }

    private fun handleObserverError(e: Throwable) {
        if (operation !== ConversationOperationEnum.OPS_CODE_JOIN_ROOM || e !is HttpException) {
            showResultImage(false, false)
        } else {
            val response = e.response()
            if (response != null && response.code() == 403) {
                ApplicationWideMessageHolder.getInstance()
                    .setMessageType(ApplicationWideMessageHolder.MessageType.CALL_PASSWORD_WRONG)
                getRouter().popCurrentController()
            } else {
                showResultImage(false, false)
            }
        }
        dispose()
    }

    private inner class GenericOperationsObserver : Observer<GenericOverall> {
        override fun onSubscribe(d: Disposable) {
            disposable = d
        }

        override fun onNext(genericOverall: GenericOverall) {
            if (operation !== ConversationOperationEnum.OPS_CODE_JOIN_ROOM) {
                showResultImage(true, false)
            } else {
                throw IllegalArgumentException("Unsupported operation code observed!")
            }
        }

        override fun onError(e: Throwable) {
            handleObserverError(e)
        }

        override fun onComplete() {
            dispose()
        }
    }

    private inner class RoomOperationsObserver : Observer<RoomOverall> {
        override fun onSubscribe(d: Disposable) {
            disposable = d
        }

        override fun onNext(roomOverall: RoomOverall) {
            conversation = roomOverall.getOcs().getData()
            if (operation !== ConversationOperationEnum.OPS_CODE_JOIN_ROOM) {
                showResultImage(true, false)
            } else {
                conversation = roomOverall.getOcs().getData()
                initiateConversation(true)
            }
        }

        override fun onError(e: Throwable) {
            handleObserverError(e)
        }

        override fun onComplete() {
            dispose()
        }
    }

    companion object {
        private const val TAG = "OperationsMenuController"
    }

    init {
        sharedApplication!!.componentApplication.inject(this)

        operation = args.getSerializable(BundleKeys.KEY_OPERATION_CODE) as ConversationOperationEnum
        if (args.containsKey(BundleKeys.KEY_ROOM)) {
            conversation = Parcels.unwrap<Conversation>(args.getParcelable<Parcelable>(BundleKeys.KEY_ROOM))
        }
        callPassword = args.getString(BundleKeys.KEY_CONVERSATION_PASSWORD, "")
        callUrl = args.getString(BundleKeys.KEY_CALL_URL, "")
        if (args.containsKey(BundleKeys.KEY_INVITED_PARTICIPANTS)) {
            invitedUsers = args.getStringArrayList(BundleKeys.KEY_INVITED_PARTICIPANTS) as ArrayList<String>
        }
        if (args.containsKey(BundleKeys.KEY_INVITED_GROUP)) {
            invitedGroups = args.getStringArrayList(BundleKeys.KEY_INVITED_GROUP) as ArrayList<String>
        }
        if (args.containsKey(BundleKeys.KEY_CONVERSATION_TYPE)) {
            conversationType =
                Parcels.unwrap<Conversation.ConversationType>(args.getParcelable<Parcelable>(BundleKeys.KEY_CONVERSATION_TYPE))
        }
        if (args.containsKey(BundleKeys.KEY_SERVER_CAPABILITIES)) {
            serverCapabilities =
                Parcels.unwrap<Capabilities>(args.getParcelable<Parcelable>(BundleKeys.KEY_SERVER_CAPABILITIES))
        }
        conversationName = args.getString(BundleKeys.KEY_CONVERSATION_NAME, "")
    }
}