package com.hop.mesh.ui

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

/**
 * Adapter for ViewPager2 managing the 3 main tabs: Devices, Chat, Network.
 */
class ViewPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {

    override fun getItemCount(): Int = 3

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> DevicesFragment()
            1 -> ChatFragment()
            2 -> NetworkFragment()
            else -> throw IllegalStateException("Invalid tab position: $position")
        }
    }
}
