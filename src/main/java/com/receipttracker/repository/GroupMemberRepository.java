package com.receipttracker.repository;

import com.receipttracker.model.ExpenseGroup;
import com.receipttracker.model.GroupMember;
import com.receipttracker.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface GroupMemberRepository extends JpaRepository<GroupMember, Long> {
    List<GroupMember> findByGroup(ExpenseGroup group);
    List<GroupMember> findByUser(User user);
    Optional<GroupMember> findByGroupAndUser(ExpenseGroup group, User user);
    boolean existsByGroupAndUser(ExpenseGroup group, User user);

    @Query("SELECT gm.group FROM GroupMember gm WHERE gm.user = :user")
    List<ExpenseGroup> findGroupsByUser(User user);
}
